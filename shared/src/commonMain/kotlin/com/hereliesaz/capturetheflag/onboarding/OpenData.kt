package com.hereliesaz.capturetheflag.onboarding

import com.hereliesaz.capturetheflag.geo.GeoPoint
import com.hereliesaz.capturetheflag.geo.Polygon
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Real sources, all open data, all free:
 *  - city limits from OpenStreetMap via Nominatim,
 *  - population from WorldPop's 100 m global grid,
 *  - buildings, water and barriers from OpenStreetMap via Overpass.
 *
 * Meant to run server-side, once per city. Each service has a usage policy; the client
 * identifies itself and [CityOnboarding] limits concurrency.
 */
class OpenData(
    private val http: HttpClient = defaultClient(),
    private val nominatim: String = "https://nominatim.openstreetmap.org",
    private val worldPop: String = "https://api.worldpop.org/v1",
    private val overpass: String = "https://overpass-api.de/api/interpreter",
) {
    private val json = Json { ignoreUnknownKeys = true }

    val boundaries = BoundarySource { name ->
        val body = http.get("$nominatim/search") {
            parameter("q", name); parameter("format", "json"); parameter("polygon_geojson", 1)
            parameter("limit", 1); parameter("featuretype", "city")
        }.bodyAsText()
        val hit = json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return@BoundarySource null
        val ring = largestRing(hit["geojson"]?.jsonObject ?: return@BoundarySource null) ?: return@BoundarySource null
        val display = hit["display_name"]?.jsonPrimitive?.content ?: name
        val short = hit["name"]?.jsonPrimitive?.content ?: display.substringBefore(',')
        FoundCity(short.lowercase(), short, display, Polygon(simplify(ring, 2_000)))
    }

    val population = PopulationSource { area ->
        val geo = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", buildJsonArray {
                add(buildJsonObject {
                    put("type", "Feature")
                    put("properties", JsonObject(emptyMap()))
                    put("geometry", buildJsonObject {
                        put("type", "Polygon")
                        put("coordinates", buildJsonArray { add(ring(area.ring + area.ring.first())) })
                    })
                })
            })
        }
        var reply = json.parseToJsonElement(http.get("$worldPop/services/stats") {
            parameter("dataset", "wpgppop"); parameter("year", 2020)
            parameter("geojson", geo.toString()); parameter("runasync", false)
        }.bodyAsText()).jsonObject
        // Large areas come back as a task to poll.
        var tries = 0
        while (reply["data"]?.let { it as? JsonObject }?.get("total_population") == null && tries++ < 30) {
            val task = reply["taskid"]?.jsonPrimitive?.content ?: break
            delay(2_000)
            reply = json.parseToJsonElement(http.get("$worldPop/tasks/$task").bodyAsText()).jsonObject
        }
        (reply["data"] as? JsonObject)?.get("total_population")?.jsonPrimitive?.double ?: 0.0
    }

    val features = object : MapFeatureSource {
        override suspend fun buildingCount(box: BBox): Int {
            val q = "[out:json][timeout:25];(way[\"building\"](${box.q});relation[\"building\"](${box.q}););out count;"
            val tags = elements(q).firstOrNull()?.jsonObject?.get("tags")?.jsonObject
            return tags?.get("total")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        }

        override suspend fun water(box: BBox): List<Polygon> {
            val q = "[out:json][timeout:90];(way[\"natural\"=\"water\"](${box.q});way[\"waterway\"=\"riverbank\"](${box.q});" +
                "relation[\"natural\"=\"water\"](${box.q}););out geom qt;"
            return elements(q).flatMap { e ->
                val o = e.jsonObject
                val rings = if (o["type"]?.jsonPrimitive?.content == "relation") {
                    o["members"]?.jsonArray.orEmpty().map { it.jsonObject }
                        .filter { it["role"]?.jsonPrimitive?.content == "outer" }.mapNotNull { geometry(it) }
                } else listOfNotNull(geometry(o))
                // Only closed rings are areas; open member ways of a multipolygon are skipped.
                rings.filter { it.size >= 4 && it.first() == it.last() }.map { Polygon(it.dropLast(1)) }
            }
        }

        override suspend fun barriers(box: BBox): List<Barrier> {
            val q = "[out:json][timeout:90];(way[\"waterway\"~\"^(river|canal)$\"](${box.q});way[\"railway\"=\"rail\"](${box.q});" +
                "way[\"highway\"~\"^(motorway|trunk)$\"](${box.q}););out geom qt;"
            return elements(q).mapNotNull { e ->
                val o = e.jsonObject
                val tags = o["tags"]?.jsonObject ?: return@mapNotNull null
                val weight = when {
                    tags["waterway"]?.jsonPrimitive?.content == "river" -> 1.0
                    tags["highway"]?.jsonPrimitive?.content == "motorway" -> 0.8
                    tags["waterway"]?.jsonPrimitive?.content == "canal" -> 0.7
                    tags["railway"] != null -> 0.6
                    else -> 0.5
                }
                geometry(o)?.takeIf { it.size >= 2 }?.let { Barrier(it, weight) }
            }
        }
    }

    private suspend fun elements(query: String): List<JsonElement> {
        val body = http.submitForm(overpass, parameters { append("data", query) }).bodyAsText()
        return json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray.orEmpty()
    }

    private val BBox.q get() = "$south,$west,$north,$east"

    private fun geometry(o: JsonObject): List<GeoPoint>? = o["geometry"]?.jsonArray?.map {
        val p = it.jsonObject
        GeoPoint(p.getValue("lat").jsonPrimitive.double, p.getValue("lon").jsonPrimitive.double)
    }

    private fun ring(points: List<GeoPoint>) = buildJsonArray {
        points.forEach { p -> add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(p.lng)); add(kotlinx.serialization.json.JsonPrimitive(p.lat)) }) }
    }

    companion object {
        fun defaultClient() = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 120_000 }
            defaultRequest { header("User-Agent", "CaptureTheFlag/0.1 (city onboarding)") }
        }

        /** The outer ring with the largest area from a GeoJSON Polygon or MultiPolygon ([lng, lat] order). */
        internal fun largestRing(geo: JsonObject): List<GeoPoint>? {
            val coords = geo["coordinates"]?.jsonArray ?: return null
            val outers: List<JsonArray> = when (geo["type"]?.jsonPrimitive?.content) {
                "Polygon" -> listOf(coords[0].jsonArray)
                "MultiPolygon" -> coords.map { it.jsonArray[0].jsonArray }
                else -> return null
            }
            return outers.map { r -> r.map { pt -> pt.jsonArray.let { GeoPoint(it[1].jsonPrimitive.double, it[0].jsonPrimitive.double) } } }
                .maxByOrNull { shoelace(it) }
                ?.let { if (it.size > 1 && it.first() == it.last()) it.dropLast(1) else it }
        }

        private fun shoelace(r: List<GeoPoint>) = abs(r.zipWithNext().sumOf { (a, b) -> a.lng * b.lat - b.lng * a.lat }) / 2

        /** Keeps every k-th vertex so point-in-polygon stays cheap on detailed coastlines. */
        internal fun simplify(r: List<GeoPoint>, max: Int): List<GeoPoint> =
            if (r.size <= max) r else r.filterIndexed { i, _ -> i % (r.size / max + 1) == 0 }
    }
}
