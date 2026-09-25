package com.hereliesaz.capturetheflag.onboarding

import com.hereliesaz.capturetheflag.model.City
import com.hereliesaz.capturetheflag.rules.CityCell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

/** Where a city's first-time setup has got to. Shown to the player who triggered it, and anyone who arrives mid-way. */
sealed interface Onboarding {
    val city: String

    data class Working(override val city: String, val stage: Stage, val done: Int = 0, val of: Int = 0) : Onboarding
    data class Ready(override val city: String, val result: OnboardedCity) : Onboarding
    data class Failed(override val city: String, val reason: String) : Onboarding

    enum class Stage(val label: String) {
        FINDING("Finding the city limits"),
        GRIDDING("Laying the grid"),
        PEOPLE("Counting the people"),
        BUILDINGS("Counting the buildings"),
        WATER("Mapping the water"),
        BARRIERS("Tracing rivers, rails and highways"),
    }
}

/** Everything the game needs about a city, gathered once, kept for good. */
data class OnboardedCity(val city: City, val cells: List<CityCell>)

/**
 * The first time anyone registers in a city, this gathers what the split needs: the city
 * limits, a grid over them, and per cell the population, buildings, land and barriers.
 * Later players in the same city get the stored result. Concurrent first requests share one run.
 */
class CityOnboarding(
    private val boundaries: BoundarySource,
    private val population: PopulationSource,
    private val features: MapFeatureSource,
    /** Parallel calls to the per-cell services. The public ones ask for politeness. */
    private val concurrency: Int = 4,
) {
    private val known = mutableMapOf<String, OnboardedCity>()
    private val running = mutableMapOf<String, CompletableDeferred<OnboardedCity?>>()
    private val states = mutableMapOf<String, MutableStateFlow<Onboarding?>>()
    private val lock = Mutex()

    fun state(cityName: String): StateFlow<Onboarding?> = slot(cityName).asStateFlow()

    private fun slot(name: String) = states.getOrPut(key(name)) { MutableStateFlow(null) }

    private fun key(name: String) = name.trim().lowercase()

    /** The city, onboarding it first if nobody has. Null if it can't be found or gathered. */
    suspend fun resolve(cityName: String): OnboardedCity? {
        val k = key(cityName)
        val (job, mine) = lock.withLock {
            known[k]?.let { return it }
            running[k]?.let { it to false } ?: (CompletableDeferred<OnboardedCity?>().also { running[k] = it } to true)
        }
        if (!mine) return job.await()
        val result = runCatching { gather(cityName) }.getOrElse { e ->
            slot(cityName).value = Onboarding.Failed(cityName, e.message ?: "Something went wrong gathering the city")
            null
        }
        lock.withLock {
            running.remove(k)
            if (result != null) known[k] = result
        }
        job.complete(result)
        return result
    }

    private suspend fun gather(cityName: String): OnboardedCity? {
        val s = slot(cityName)
        fun at(stage: Onboarding.Stage, done: Int = 0, of: Int = 0) { s.value = Onboarding.Working(cityName, stage, done, of) }

        at(Onboarding.Stage.FINDING)
        val found = boundaries.find(cityName) ?: run {
            s.value = Onboarding.Failed(cityName, "Couldn't find a city called \"$cityName\"")
            return null
        }

        at(Onboarding.Stage.GRIDDING)
        val grid = Grid.lay(found.boundary)
        if (grid.size < 2) {
            s.value = Onboarding.Failed(cityName, "${found.name} is too small to split")
            return null
        }
        val cityBox = BBox.of(found.boundary.ring)

        val people = perCell(grid, Onboarding.Stage.PEOPLE, ::at) { population.population(it.polygon) }
        val buildings = perCell(grid, Onboarding.Stage.BUILDINGS, ::at) { features.buildingCount(it.box).toDouble() }

        at(Onboarding.Stage.WATER)
        val water = features.water(cityBox)
        at(Onboarding.Stage.BARRIERS)
        val barriers = features.barriers(cityBox)

        val cells = grid.mapIndexed { i, c ->
            CityCell(
                center = c.center,
                population = people[i],
                buildings = buildings[i],
                landAreaM2 = c.areaM2 * Grid.landFraction(c, water),
                barrier = Grid.barrierScore(c, barriers),
            )
        }
        // Rough local time from longitude. Good enough for night-time perks; not for clocks.
        val offset = (found.boundary.ring.map { it.lng }.average() / 15).roundToInt() * 60
        val result = OnboardedCity(City(found.id, found.name, found.boundary, offset), cells)
        s.value = Onboarding.Ready(cityName, result)
        return result
    }

    private suspend fun perCell(
        grid: List<GridCell>,
        stage: Onboarding.Stage,
        at: (Onboarding.Stage, Int, Int) -> Unit,
        f: suspend (GridCell) -> Double,
    ): List<Double> = coroutineScope {
        val gate = Semaphore(concurrency)
        var done = 0
        val progress = Mutex()
        at(stage, 0, grid.size)
        grid.map { c ->
            async {
                val v = gate.withPermit { runCatching { f(c) }.getOrDefault(0.0) }
                progress.withLock { done++; at(stage, done, grid.size) }
                v
            }
        }.awaitAll()
    }
}
