package net.osmand.plus.plugins.flightmode

import kotlin.coroutines.coroutineContext
import kotlin.math.*
import kotlinx.coroutines.ensureActive

data class FlightOfflineRequest(val tile: TerrainTileId, val satellite: Boolean, val band: Int)

data class FlightOfflineQuote(
    val requests: List<FlightOfflineRequest>,
    val route: List<Pair<Double, Double>>,
    val bands: List<FlightOfflineBand>,
) {
    val estimatedBytes: Long
        get() = requests.sumOf { if (it.satellite) 45_000L else 110_000L }

    val assets
        get() =
            FlightOfflineAssets(
                requests.filter { !it.satellite }.map { it.tile },
                requests.filter { it.satellite }.map { it.tile },
            )
}

/** Conservative spherical corridor cover. Requested zooms are never silently lowered. */
object FlightOfflinePreparation {
    const val MAX_REQUESTS = 250_000

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
        val duration =
            (prep.arrivalMillis - start).takeIf { it > 0 }
                ?: (total / 750 * 3_600_000).toLong().coerceAtLeast(600_000)
        val count = (duration / 15_000).toInt().coerceIn(60, 4000)
        val cruise = min(12_000.0, max(2000.0, total * 25))
        val samples =
            (0..count).map { i ->
                val f = i.toDouble() / count
                var d = f * total
                var leg = 0
                while (leg < distances.lastIndex && d > distances[leg]) {
                    d -= distances[leg]
                    leg++
                }
                val at =
                    FlightTerrainTilePlanner.greatCircleInterpolate(
                        coordinates[leg],
                        coordinates[leg + 1],
                        (d / distances[leg].coerceAtLeast(0.001)).coerceIn(0.0, 1.0),
                    )
                FlightSample(
                    i,
                    0,
                    start + (f * duration).toLong(),
                    at.first,
                    at.second,
                    cruise * min(1.0, min(f / 0.15, (1 - f) / 0.18)),
                    (total * 1_000_000 / duration).toFloat(),
                    null,
                    null,
                )
            }
        return recordedFlightTrip("Simulation", FlightTrackMath.fillMissingBearings(samples))
    }
}
