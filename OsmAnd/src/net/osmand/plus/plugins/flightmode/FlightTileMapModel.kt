package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Normalized Mercator coordinates. The inspection grid never owns or changes this camera. */
internal data class FlightTileMapCamera(
    val x: Double = .5,
    val y: Double = .5,
    val worldPixels: Double = 512.0,
) {
    fun pan(dx: Double, dy: Double) =
        copy(x = x - dx / worldPixels, y = (y - dy / worldPixels).coerceIn(0.0, 1.0))

    fun zoom(
        factor: Double,
        focusX: Double,
        focusY: Double,
        width: Int,
        height: Int,
    ): FlightTileMapCamera {
        val next = (worldPixels * factor).coerceIn(maxOf(width, height) * .75, 256.0 * (1 shl 19))
        return copy(
            x = x + (focusX - width / 2.0) * (1 / worldPixels - 1 / next),
            y = (y + (focusY - height / 2.0) * (1 / worldPixels - 1 / next)).coerceIn(0.0, 1.0),
            worldPixels = next,
        )
    }

    fun bounds(width: Int, height: Int) =
        FlightTileMapBounds(
            x - width / 2.0 / worldPixels,
            x + width / 2.0 / worldPixels,
            max(0.0, y - height / 2.0 / worldPixels),
            min(1.0, y + height / 2.0 / worldPixels),
        )

    companion object {
        fun fit(route: List<Pair<Double, Double>>, width: Int, height: Int): FlightTileMapCamera? {
            return fitProjected(flightTileMapRoute(route), width, height)
        }

        fun fitProjected(
            points: List<Pair<Double, Double>>,
            width: Int,
            height: Int,
        ): FlightTileMapCamera? {
            if (points.isEmpty() || width <= 0 || height <= 0) return null
            val minX = points.minOf { it.first }
            val maxX = points.maxOf { it.first }
            val minY = points.minOf { it.second }
            val maxY = points.maxOf { it.second }
            val scale =
                min(
                    width * .84 / (maxX - minX).coerceAtLeast(.002),
                    height * .84 / (maxY - minY).coerceAtLeast(.002),
                )
            return FlightTileMapCamera((minX + maxX) / 2, (minY + maxY) / 2, scale)
        }
    }
}

internal data class FlightTileMapBounds(
    val left: Double,
    val right: Double,
    val top: Double,
    val bottom: Double,
) {
    fun range(z: Int): Pair<IntRange, IntRange> {
        val n = 1 shl z
        val x0 = floor(left * n).toInt()
        return (x0..minOf(floor(right * n).toInt(), x0 + n)) to
            (floor(top * n).toInt().coerceIn(0, n - 1)..floor(bottom * n)
                    .toInt()
                    .coerceIn(0, n - 1))
    }

    fun gridZoom(requested: Int): Int {
        var z = requested.coerceIn(0, 22)
        while (z > 0) {
            val (xs, ys) = range(z)
            if ((xs.last - xs.first + 1L) * (ys.last - ys.first + 1L) <= 512L) break
            z--
        }
        return z
    }
}

/** A finer level may cover only part of a square; never advertise it as complete. */
internal data class FlightTileLevelPortion(val zoom: Int, val files: Int, val capacity: Long) {
    val full: Boolean
        get() = files.toLong() >= capacity

    fun compact(): String = if (full) "z$zoom" else "z$zoom($files/$capacity)"
}

internal data class FlightTileMapCell(
    val id: TerrainTileId,
    val displayX: Int,
    val satellite: List<FlightTileLevelPortion>,
    val terrain: List<FlightTileLevelPortion>,
    val requestedSatellite: List<FlightTileLevelPortion>,
    val requestedTerrain: List<FlightTileLevelPortion>,
    val required: Int,
    val presentRequired: Int,
    val inventoryComplete: Boolean,
) {
    val state: Int
        get() =
            when {
                required > 0 && presentRequired == required -> 0 // all requested files present
                required > 0 && !inventoryComplete -> 1 // not inventoried, not a known hole
                required > 0 -> 2 // at least one requested file missing
                satellite.isNotEmpty() || terrain.isNotEmpty() ->
                    3 // cached, not requested by this flight
                else -> 4 // nothing
            }
}

internal object FlightTileMapModel {
    private class Cell(val id: TerrainTileId, val displayX: Int) {
        val available = arrayOf(sortedMapOf<Int, Int>(), sortedMapOf<Int, Int>())
        val requested = arrayOf(sortedMapOf<Int, Int>(), sortedMapOf<Int, Int>())
        var required = 0
        var present = 0
    }

    /** Bounded visible grid. All levels in the shared store participate, not only this manifest. */
    fun cells(
        bounds: FlightTileMapBounds,
        zoom: Int,
        snapshot: FlightTileCatalog.Snapshot,
        requests: List<FlightOfflineRequest>,
    ): List<FlightTileMapCell> {
        val z = bounds.gridZoom(zoom)
        val n = 1 shl z
        val (xs, ys) = bounds.range(z)
        val cells = linkedMapOf<Pair<Int, Int>, Cell>()
        for (y in ys) for (x in xs) cells[x to y] =
            Cell(TerrainTileId(z, Math.floorMod(x, n), y), x)
        fun visit(key: FlightOfflineTileKey, action: (Cell) -> Unit) {
            val tile = key.tile
            if (tile.zoom >= z) {
                val shift = tile.zoom - z
                val x = tile.x shr shift
                val y = tile.y shr shift
                if (y !in ys) return
                val displayX = xs.first + Math.floorMod(x - xs.first, n)
                cells[displayX to y]?.let(action)
                cells[(displayX + n) to y]?.let(action)
            } else {
                val shift = z - tile.zoom
                val firstY = maxOf(ys.first, tile.y shl shift)
                val lastY = minOf(ys.last, ((tile.y + 1) shl shift) - 1)
                if (firstY > lastY) return
                for (x in xs) if ((Math.floorMod(x, n) shr shift) == tile.x)
                    for (y in firstY..lastY) cells[x to y]?.let(action)
            }
        }
        for ((key, _) in snapshot.files) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            visit(key) { cell ->
                val group = cell.available[if (key.satellite) 0 else 1]
                group[key.tile.zoom] = (group[key.tile.zoom] ?: 0) + 1
            }
        }
        // Quotes already de-duplicate requests by source, z/x/y.
        for (request in requests) {
            if (Thread.currentThread().isInterrupted) return emptyList()
            val key = FlightOfflineTileKey(request.tile, request.satellite)
            visit(key) { cell ->
                val group = cell.requested[if (key.satellite) 0 else 1]
                group[key.tile.zoom] = (group[key.tile.zoom] ?: 0) + 1
                cell.required++
                if (snapshot.files.containsKey(key)) cell.present++
            }
        }
        fun portions(groups: Map<Int, Int>) =
            groups.map { (level, count) ->
                FlightTileLevelPortion(
                    level,
                    count,
                    if (level <= z) 1L else 1L shl (2 * (level - z)),
                )
            }
        return cells.values.map { c ->
            FlightTileMapCell(
                c.id,
                c.displayX,
                portions(c.available[0]),
                portions(c.available[1]),
                portions(c.requested[0]),
                portions(c.requested[1]),
                c.required,
                c.present,
                snapshot.complete,
            )
        }
    }
}

/** Unwrap the date line; densify planned great-circle legs so Fit route includes their arc. */
internal fun flightTileMapRoute(route: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (route.isEmpty()) return emptyList()
    val coordinates = arrayListOf(route.first())
    for ((from, to) in route.zipWithNext()) {
        val steps =
            ceil(
                    FlightTerrainTilePlanner.distanceKm(
                        from.first,
                        from.second,
                        to.first,
                        to.second,
                    ) / 100
                )
                .toInt()
                .coerceIn(1, 200)
        for (i in 1..steps) coordinates +=
            FlightTerrainTilePlanner.greatCircleInterpolate(from, to, i.toDouble() / steps)
    }
    var previous = FlightTerrainTilePlanner.longitudeToTileX(coordinates.first().second, 0)
    return coordinates.map { (lat, lon) ->
        val rawX = FlightTerrainTilePlanner.longitudeToTileX(lon, 0)
        val x = rawX + round(previous - rawX)
        previous = x
        x to FlightTerrainTilePlanner.latitudeToTileY(lat.coerceIn(-85.0511, 85.0511), 0)
    }
}
