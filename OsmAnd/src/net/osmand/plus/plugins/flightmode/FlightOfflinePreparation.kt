package net.osmand.plus.plugins.flightmode

import kotlin.coroutines.coroutineContext
import kotlin.math.*
import kotlinx.coroutines.ensureActive

data class FlightOfflineRequest(val tile: TerrainTileId, val satellite: Boolean, val band: Int)

// Identity equality is deliberate: Compose must not hash a 250,000-entry manifest every frame.
class FlightOfflineQuote(
    val requests: List<FlightOfflineRequest>,
    val route: List<Pair<Double, Double>>,
    val bands: List<FlightOfflineBand>,
) {
    val satelliteCount = requests.count { it.satellite }
    val terrainCount = requests.size - satelliteCount
    val satelliteEstimatedBytes = FlightOfflineSizeEstimate.bytes(satelliteCount, 0)
    val terrainEstimatedBytes = FlightOfflineSizeEstimate.bytes(0, terrainCount)
    val estimatedBytes = satelliteEstimatedBytes + terrainEstimatedBytes
    val distanceKm =
        route.zipWithNext().sumOf { (a, b) ->
            FlightTerrainTilePlanner.distanceKm(a.first, a.second, b.first, b.second)
        }
    // Precomputed off the UI thread. Each source tile is counted once, including shared ancestors.
    val bandEstimatedBytes: Map<Int, Long> =
        requests
            .groupBy { it.band }
            .mapValues { (_, items) ->
                val satellite = items.count { it.satellite }
                FlightOfflineSizeEstimate.bytes(satellite, items.size - satellite)
            }

    val assets =
        FlightOfflineAssets(
            requests.filter { !it.satellite }.map { it.tile },
            requests.filter { it.satellite }.map { it.tile },
        )

    /** Bounded overview, constructed once off the UI thread, not once per progress notification. */
    fun preview(satellite: Boolean, colors: List<Int>): List<Pair<TerrainTileId, Int>> {
        for (maximumZoom in 8 downTo 3) {
            val cells = linkedMapOf<TerrainTileId, Int>()
            requests
                .asSequence()
                .filter { it.band >= 0 && it.satellite == satellite }
                .forEach { r ->
                    val z = min(r.tile.zoom, maximumZoom)
                    val shift = r.tile.zoom - z
                    val id = TerrainTileId(z, r.tile.x shr shift, r.tile.y shr shift)
                    cells[id] = minOf(cells[id] ?: r.band, r.band)
                }
            if (cells.size <= 8192 || maximumZoom == 3)
                return cells.entries
                    .sortedByDescending { it.value }
                    .map { it.key to colors[it.value % colors.size] }
        }
        return emptyList()
    }
}

/** Conservative spherical corridor cover. Requested zooms are never silently lowered. */
object FlightOfflinePreparation {
    const val MAX_REQUESTS = 250_000

    /** The same requested coverage as the downloader, including legacy Standard corridors. */
    suspend fun corridorQuote(plan: FlightPlan, trip: FlightTrip?): FlightOfflineQuote {
        if (plan.preparation != null) return quote(plan)
        val tiles =
            trip
                ?.samples
                ?.takeIf { it.size >= 2 }
                ?.let { FlightTerrainTilePlanner.trackCorridorPlan(it, plan.terrainCorridorKm) }
                ?: FlightTerrainTilePlanner.corridorPlan(plan.stops, plan.terrainCorridorKm)
        requireNotNull(tiles) { "No route available for offline coverage" }
        return FlightOfflineQuote(
            tiles.tiles.flatMap {
                listOf(FlightOfflineRequest(it, false, 0), FlightOfflineRequest(it, true, 0))
            },
            plan.stops.mapNotNull { stop ->
                stop.latitude?.let { lat -> stop.longitude?.let { lat to it } }
            },
            emptyList(),
        )
    }

    fun canSimulate(plan: FlightPlan): Boolean =
        plan.stops.size >= 2 &&
            plan.stops.all {
                it.latitude?.let { lat -> lat.isFinite() && abs(lat) < 85.0511 } == true &&
                    it.longitude?.let { lon -> lon.isFinite() && lon in -180.0..180.0 } == true
            } &&
            plan.stops.zipWithNext().any { (a, b) ->
                a.latitude != b.latitude || a.longitude != b.longitude
            }

    fun simulationInput(plan: FlightPlan) =
        Triple(
            plan.stops.map { Triple(it.latitude, it.longitude, it.type) },
            plan.preparation?.departureMillis ?: 0L,
            plan.preparation?.arrivalMillis ?: 0L,
        )

    suspend fun quote(plan: FlightPlan): FlightOfflineQuote {
        val stops =
            plan.stops.mapNotNull { s -> s.latitude?.let { lat -> s.longitude?.let { lat to it } } }
        require(stops.size >= 2 && stops.size == plan.stops.size) {
            "Place every route point on the map"
        }
        val bands = (plan.preparation ?: FlightPreparation()).bands.sortedBy { it.radiusKm }
        require(
            stops.all {
                it.first.isFinite() &&
                    it.second.isFinite() &&
                    abs(it.first) < 85.0511 &&
                    it.second in -180.0..180.0
            }
        )
        require(
            bands.isNotEmpty() &&
                bands.all {
                    it.radiusKm in 1..600 && it.satelliteZoom in 3..14 && it.terrainZoom in 3..14
                }
        )
        val requests = linkedMapOf<Pair<Boolean, TerrainTileId>, FlightOfflineRequest>()
        // Larger bands first in the preview; the download queue below is coarse-first.
        for ((index, band) in bands.withIndex().reversed()) {
            for ((satellite, z) in listOf(true to band.satelliteZoom, false to band.terrainZoom)) {
                val radius = band.radiusKm.toDouble()
                val spacing = min(20.0, radius / 3).coerceAtLeast(0.2)
                for ((from, to) in stops.zipWithNext()) {
                    val distance =
                        FlightTerrainTilePlanner.distanceKm(
                            from.first,
                            from.second,
                            to.first,
                            to.second,
                        )
                    require(distance < 19_000) {
                        "Add an intermediate point for near-antipodal routes"
                    }
                    val count = ceil(distance / spacing).toInt().coerceAtLeast(1)
                    for (i in 0..count) {
                        coroutineContext.ensureActive()
                        val at =
                            FlightTerrainTilePlanner.greatCircleInterpolate(
                                from,
                                to,
                                i.toDouble() / count,
                            )
                        coverDisk(at, radius + spacing / 2, z) { tile ->
                            requests[satellite to tile] =
                                FlightOfflineRequest(tile, satellite, index)
                            check(requests.size <= MAX_REQUESTS) {
                                "More than $MAX_REQUESTS tiles: reduce the detailed radius or zoom; quality was not lowered"
                            }
                        }
                    }
                }
            }
        }
        // All ancestors guarantee a usable low-resolution fallback at every intermediate zoom.
        val fine = requests.values.toList()
        for (request in fine) {
            coroutineContext.ensureActive()
            for (z in 3 until request.tile.zoom) {
                val shift = request.tile.zoom - z
                val tile = TerrainTileId(z, request.tile.x shr shift, request.tile.y shr shift)
                requests.putIfAbsent(
                    request.satellite to tile,
                    FlightOfflineRequest(tile, request.satellite, -1),
                )
                check(requests.size <= MAX_REQUESTS) {
                    "Offline package exceeds the tile budget; reduce the detailed corridor"
                }
            }
        }
        return FlightOfflineQuote(
            requests.values.sortedWith(
                compareBy<FlightOfflineRequest> { it.tile.zoom }
                    .thenBy { it.satellite }
                    .thenBy { it.band }
            ),
            stops,
            bands,
        )
    }

    private fun coverDisk(
        center: Pair<Double, Double>,
        radiusKm: Double,
        z: Int,
        emit: (TerrainTileId) -> Unit,
    ) {
        val n = 1 shl z
        val lat = Math.toRadians(center.first)
        val angular = radiusKm / 6371.0
        require(abs(center.first) + Math.toDegrees(angular) < 85.0511) {
            "The requested corridor reaches beyond the 85° Mercator tile coverage; it cannot be marked complete"
        }
        val minLat = (center.first - Math.toDegrees(angular)).coerceAtLeast(-85.0511)
        val maxLat = (center.first + Math.toDegrees(angular)).coerceAtMost(85.0511)
        val fromY =
            floor(FlightTerrainTilePlanner.latitudeToTileY(maxLat, z)).toInt().coerceIn(0, n - 1)
        val toY =
            floor(FlightTerrainTilePlanner.latitudeToTileY(minLat, z)).toInt().coerceIn(0, n - 1)
        for (y in fromY..toY) {
            val top = Math.toRadians(FlightTerrainTilePlanner.tileYToLatitude(y.toDouble(), z))
            val bottom = Math.toRadians(FlightTerrainTilePlanner.tileYToLatitude(y + 1.0, z))
            // Longitude reach is maximized at asin(sin(latitude)/cos(radius)), not at row centre.
            val optimum = asin((sin(lat) / cos(angular)).coerceIn(-1.0, 1.0)).coerceIn(bottom, top)
            val q = (cos(angular) - sin(lat) * sin(optimum)) / (cos(lat) * cos(optimum))
            if (q > 1) continue
            val half = Math.toDegrees(acos(q.coerceIn(-1.0, 1.0)))
            val fromX = floor((center.second - half + 180) / 360 * n).toInt()
            val toX = floor((center.second + half + 180) / 360 * n).toInt()
            for (x in fromX..toX) emit(TerrainTileId(z, Math.floorMod(x, n), y))
        }
    }

    fun simulation(plan: FlightPlan): FlightTrip {
        require(canSimulate(plan)) { "Place at least two distinct route points on the map" }
        val coordinates =
            plan.stops.mapNotNull { s -> s.latitude?.let { lat -> s.longitude?.let { lat to it } } }
        require(coordinates.size >= 2)
        val distances =
            coordinates.zipWithNext().map { (a, b) ->
                FlightTerrainTilePlanner.distanceKm(a.first, a.second, b.first, b.second)
            }
        val total = distances.sum().coerceAtLeast(0.001)
        val prep = plan.preparation ?: FlightPreparation()
        val start = prep.departureMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val stopoverCount = (1 until plan.stops.lastIndex).count(plan::isIntermediateStopover)
        val duration =
            (prep.arrivalMillis - start).takeIf { it > 0 }
                ?: (total / 750 * 3_600_000).toLong().coerceAtLeast(600_000) +
                    stopoverCount * STOPOVER_MILLIS
        val dwellTotal =
            (stopoverCount * STOPOVER_MILLIS).coerceAtMost((duration - 1L).coerceAtLeast(0L))
        val dwellPerStop = if (stopoverCount == 0) 0L else dwellTotal / stopoverCount
        val flightDuration = (duration - dwellTotal).coerceAtLeast(1L)
        val targetCount = (duration / 15_000L).toInt().coerceIn(60, 4000)
        val sampleInterval = (duration / targetCount).coerceAtLeast(1L)
        val samples =
            mutableListOf(
                FlightSample(
                    0,
                    0,
                    start,
                    coordinates.first().first,
                    coordinates.first().second,
                    0.0,
                    0f,
                    null,
                    null,
                )
            )
        var timestamp = start
        var sampleIndex = 1
        var blockStart = 0
        while (blockStart < distances.size) {
            val blockEnd =
                (blockStart until distances.size).firstOrNull { leg ->
                    leg == distances.lastIndex || plan.isIntermediateStopover(leg + 1)
                } ?: distances.lastIndex
            val blockDistances = distances.subList(blockStart, blockEnd + 1)
            val blockDistance = blockDistances.sum().coerceAtLeast(0.001)
            val cruise = min(12_000.0, max(2000.0, blockDistance * 25))
            for (leg in blockStart..blockEnd) {
                val legDistance = distances[leg]
                val legDuration = (flightDuration * legDistance / total).toLong().coerceAtLeast(1L)
                val count =
                    kotlin.math
                        .ceil(legDuration / sampleInterval.toDouble())
                        .toInt()
                        .coerceAtLeast(1)
                val distanceBefore = distances.subList(blockStart, leg).sum()
                for (step in 1..count) {
                    val fraction = step.toDouble() / count
                    val at =
                        FlightTerrainTilePlanner.greatCircleInterpolate(
                            coordinates[leg],
                            coordinates[leg + 1],
                            fraction,
                        )
                    val blockProgress =
                        ((distanceBefore + legDistance * fraction) / blockDistance).coerceIn(
                            0.0,
                            1.0,
                        )
                    timestamp += (legDuration / count).coerceAtLeast(1L)
                    samples +=
                        FlightSample(
                            sampleIndex++,
                            0,
                            timestamp,
                            at.first,
                            at.second,
                            altitudeForBlock(blockProgress, cruise),
                            (legDistance * 1000 / (legDuration / 1000.0)).toFloat(),
                            null,
                            null,
                        )
                }
            }
            if (blockEnd < distances.lastIndex && dwellPerStop > 0L) {
                val count =
                    kotlin.math
                        .ceil(dwellPerStop / sampleInterval.toDouble())
                        .toInt()
                        .coerceAtLeast(1)
                repeat(count) {
                    timestamp += (dwellPerStop / count).coerceAtLeast(1L)
                    samples +=
                        FlightSample(
                            sampleIndex++,
                            0,
                            timestamp,
                            coordinates[blockEnd + 1].first,
                            coordinates[blockEnd + 1].second,
                            0.0,
                            0f,
                            null,
                            null,
                        )
                }
            }
            blockStart = blockEnd + 1
        }
        samples[samples.lastIndex] = samples.last().copy(timestampMillis = start + duration)
        return recordedFlightTrip("Simulation", FlightTrackMath.fillMissingBearings(samples))
    }

    private fun altitudeForBlock(progress: Double, cruise: Double): Double =
        when {
            progress < 0.15 -> cruise * progress / 0.15
            progress < 0.82 -> cruise
            else -> cruise * (1.0 - progress) / 0.18
        }

    private const val STOPOVER_MILLIS = 45 * 60_000L
}
