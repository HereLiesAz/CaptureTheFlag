package com.hereliesaz.capturetheflag

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import com.hereliesaz.capturetheflag.onboarding.BBox
import com.hereliesaz.capturetheflag.onboarding.Barrier
import com.hereliesaz.capturetheflag.onboarding.BoundarySource
import com.hereliesaz.capturetheflag.onboarding.CityOnboarding
import com.hereliesaz.capturetheflag.onboarding.FoundCity
import com.hereliesaz.capturetheflag.onboarding.Grid
import com.hereliesaz.capturetheflag.onboarding.MapFeatureSource
import com.hereliesaz.capturetheflag.onboarding.Onboarding
import com.hereliesaz.capturetheflag.onboarding.OpenData
import com.hereliesaz.capturetheflag.onboarding.PopulationSource
import com.hereliesaz.capturetheflag.rules.CityPartitioner
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingTest {
    // ~11 km × 11 km square.
    private val square = Polygon(listOf(GeoPoint(30.0, -90.1), GeoPoint(30.0, -90.0), GeoPoint(30.1, -90.0), GeoPoint(30.1, -90.1)))

    @Test fun gridFitsTheCityAndSkipsOutside() {
        val cells = Grid.lay(square, target = 100)
        assertTrue(cells.size in 80..130, "${cells.size}")
        assertTrue(cells.all { it.center in square })
        // A triangle keeps roughly half.
        val tri = Polygon(listOf(GeoPoint(30.0, -90.1), GeoPoint(30.0, -90.0), GeoPoint(30.1, -90.1)))
        assertTrue(Grid.lay(tri, target = 100).all { it.center in tri })
    }

    @Test fun waterAndBarriersAreMeasuredPerCell() {
        val cell = Grid.lay(square, target = 4).first()
        val lake = Polygon(listOf(
            GeoPoint(cell.box.south, cell.box.west), GeoPoint(cell.box.south, (cell.box.west + cell.box.east) / 2),
            GeoPoint(cell.box.north, (cell.box.west + cell.box.east) / 2), GeoPoint(cell.box.north, cell.box.west),
        ))
        assertEquals(0.5, Grid.landFraction(cell, listOf(lake)), 0.01)
        assertEquals(1.0, Grid.landFraction(cell, emptyList()))
        val river = Barrier(listOf(GeoPoint(cell.box.south - 1, cell.center.lng), GeoPoint(cell.box.north + 1, cell.center.lng)), 1.0)
        val farRail = Barrier(listOf(GeoPoint(40.0, -80.0), GeoPoint(40.1, -80.0)), 0.6)
        assertEquals(1.0, Grid.barrierScore(cell, listOf(river, farRail)))
        assertEquals(0.0, Grid.barrierScore(cell, listOf(farRail)))
    }

    private class Fakes(val found: FoundCity?) {
        var boundaryCalls = 0
        val boundaries = BoundarySource { boundaryCalls++; found }
        // Everyone lives in the west half.
        val population = PopulationSource { a -> if (a.ring.first().lng < -90.05) 1_000.0 else 10.0 }
        val features = object : MapFeatureSource {
            override suspend fun buildingCount(box: BBox) = 50
            override suspend fun water(box: BBox) = emptyList<Polygon>()
            override suspend fun barriers(box: BBox) = listOf(Barrier(listOf(GeoPoint(29.9, -90.05), GeoPoint(30.2, -90.05)), 1.0))
        }
    }

    @Test fun firstRegistrationGathersOnceAndEveryoneShares() = runTest {
        val f = Fakes(FoundCity("testville", "Testville", "Testville, Nowhere", square))
        val o = CityOnboarding(f.boundaries, f.population, f.features)
        val results = (1..5).map { async { o.resolve("Testville") } }.awaitAll()
        assertTrue(results.all { it != null && it === results.first() })
        assertEquals(1, f.boundaryCalls)
        assertIs<Onboarding.Ready>(o.state("testville").value)
        assertNotNull(o.resolve("  TESTVILLE "))
        assertEquals(1, f.boundaryCalls)
        val city = results.first()!!
        assertEquals(-360, city.city.utcOffsetMinutes)
        // The gathered grid is good enough to split: the river line is found and followed.
        val cut = CityPartitioner(topK = 1).partition(city.cells, Random(0))
        assertTrue(cut.breakdown.getValue("population") < 0.2, cut.breakdown.toString())
    }

    @Test fun unknownCitiesFailWithAReason() = runTest {
        val o = CityOnboarding(Fakes(null).boundaries, Fakes(null).population, Fakes(null).features)
        assertNull(o.resolve("Atlantis"))
        val s = o.state("Atlantis").value
        assertIs<Onboarding.Failed>(s)
        assertTrue("Atlantis" in s.reason)
    }

    @Test fun openDataParsesTheRealResponseShapes() = runTest {
        val engine = MockEngine { req ->
            val url = req.url.toString()
            val body = when {
                "nominatim" in url -> """[{"name":"Testville","display_name":"Testville, Nowhere","geojson":{"type":"MultiPolygon","coordinates":[
                    [[[-90.1,30.0],[-90.0,30.0],[-90.0,30.1],[-90.1,30.1],[-90.1,30.0]]],
                    [[[-89.0,30.0],[-88.99,30.0],[-88.99,30.01],[-89.0,30.0]]]]}}]"""
                "worldpop" in url -> """{"status":"finished","error":false,"data":{"total_population":1172.36},"taskid":"x"}"""
                "overpass" in url -> {
                    val q = (req.body as FormDataContent).formData["data"]!!
                    when {
                        "out count" in q -> """{"elements":[{"type":"count","id":0,"tags":{"nodes":"0","ways":"120","relations":"3","total":"123"}}]}"""
                        "natural" in q -> """{"elements":[{"type":"way","id":1,"geometry":[{"lat":30.0,"lon":-90.1},{"lat":30.0,"lon":-90.09},{"lat":30.01,"lon":-90.09},{"lat":30.0,"lon":-90.1}]}]}"""
                        else -> """{"elements":[{"type":"way","id":2,"tags":{"waterway":"river"},"geometry":[{"lat":30.0,"lon":-90.05},{"lat":30.1,"lon":-90.05}]},
                                     {"type":"way","id":3,"tags":{"railway":"rail"},"geometry":[{"lat":30.05,"lon":-90.1},{"lat":30.05,"lon":-90.0}]}]}"""
                    }
                }
                else -> "{}"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val open = OpenData(HttpClient(engine))
        val found = open.boundaries.find("testville")!!
        assertEquals("testville", found.id)
        assertEquals(4, found.boundary.ring.size) // the big ring, closing vertex dropped
        assertEquals(1172.36, open.population.population(square))
        val box = BBox.of(square.ring)
        assertEquals(123, open.features.buildingCount(box))
        assertEquals(1, open.features.water(box).size)
        val barriers = open.features.barriers(box)
        assertEquals(listOf(1.0, 0.6), barriers.map { it.weight })
    }
}
