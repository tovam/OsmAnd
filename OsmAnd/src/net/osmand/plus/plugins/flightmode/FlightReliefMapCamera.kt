package net.osmand.plus.plugins.flightmode

import kotlin.math.*

/** Independent geographic camera for the lower mixed viewport; widths are ground metres. */
data class FlightReliefMapPose(
    val latitude: Double,
    val longitude: Double,
    val widthMeters: Double = 50_000.0,
    val headingDegrees: Float = 0f,
    val tiltDegrees: Float = 35f,
) {
    fun clamped() =
        copy(
            latitude = latitude.takeIf { it.isFinite() }?.coerceIn(-85.0, 85.0) ?: 0.0,
            longitude =
                longitude
                    .takeIf { it.isFinite() }
                    ?.let {
                        if (it >= -180.0 && it < 180.0) it
                        else ((it % 360.0 + 540.0) % 360.0) - 180.0
                    } ?: 0.0,
            widthMeters =
                widthMeters.takeIf { it.isFinite() }?.coerceIn(500.0, 600_000.0) ?: 50_000.0,
            headingDegrees =
                headingDegrees.takeIf { it.isFinite() }?.let { (it % 360f + 360f) % 360f } ?: 0f,
            tiltDegrees = tiltDegrees.takeIf { it.isFinite() }?.coerceIn(0f, 50f) ?: 35f,
        )

    fun transform(
        dx: Float,
        dy: Float,
        zoom: Float,
        rotation: Float,
        pixelWidth: Int,
    ): FlightReliefMapPose {
        val safe = clamped()
        if (pixelWidth <= 0 || !listOf(dx, dy, zoom, rotation).all { it.isFinite() } || zoom <= 0f)
            return safe
        val heading = Math.toRadians(safe.headingDegrees.toDouble())
        val right = -dx * safe.widthMeters / pixelWidth
        val forward =
            dy * safe.widthMeters / pixelWidth / cos(Math.toRadians(safe.tiltDegrees.toDouble()))
        val coordinates = FlightTerrainCoordinates(safe.latitude, safe.longitude)
        val location =
            if (dx == 0f && dy == 0f) doubleArrayOf(safe.latitude, safe.longitude)
            else
                coordinates.toGeographic(
                    doubleArrayOf(
                        right * cos(heading) + forward * sin(heading),
                        0.0,
                        right * sin(heading) - forward * cos(heading),
                    )
                )
        return safe
            .copy(
                latitude = location[0],
                longitude = location[1],
                widthMeters = safe.widthMeters / zoom,
                headingDegrees = safe.headingDegrees - rotation,
            )
            .clamped()
    }

    fun camera(aspectRatio: Float, groundMeters: Float): FlightReliefMapCamera {
        val safe = clamped()
        val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.25f, 4f) ?: 1f
        val ground = groundMeters.takeIf { it.isFinite() } ?: 0f
        val distance = safe.widthMeters / (2 * aspect * tan(Math.toRadians(FIELD_OF_VIEW / 2.0)))
        val tilt = Math.toRadians(safe.tiltDegrees.toDouble())
        val heading = Math.toRadians(safe.headingDegrees.toDouble())
        val coordinates = FlightTerrainCoordinates(safe.latitude, safe.longitude)
        val target = coordinates.toLocal(safe.latitude, safe.longitude, ground.toDouble())
        val eye =
            coordinates.toGeographic(
                doubleArrayOf(
                    target[0] - sin(heading) * distance * sin(tilt),
                    target[1] + distance * cos(tilt),
                    target[2] + cos(heading) * distance * sin(tilt),
                )
            )
        return FlightReliefMapCamera(safe, eye[0], eye[1], eye[2].toFloat(), ground, FIELD_OF_VIEW)
    }

    companion object {
        const val FIELD_OF_VIEW = 45f
    }
}

data class FlightReliefMapCamera(
    val pose: FlightReliefMapPose,
    val eyeLatitude: Double,
    val eyeLongitude: Double,
    val eyeAltitudeMeters: Float,
    val groundMeters: Float,
    val fieldOfViewDegrees: Float,
) {
    fun direction(coordinates: FlightTerrainCoordinates, eye: FloatArray): FloatArray {
        val target = coordinates.toLocal(pose.latitude, pose.longitude, groundMeters.toDouble())
        return FloatArray(3) { target[it] - eye[it] }
    }

    fun up(coordinates: FlightTerrainCoordinates): FloatArray {
        val heading = Math.toRadians(pose.headingDegrees.toDouble())
        val tilt = Math.toRadians(pose.tiltDegrees.toDouble())
        return coordinates.vectorToLocal(
            pose.latitude,
            pose.longitude,
            (sin(heading) * cos(tilt)).toFloat(),
            sin(tilt).toFloat(),
            (-cos(heading) * cos(tilt)).toFloat(),
        )
    }
}

/** Refinements already use UVs within their base texture, including at the date line. */
internal fun flightReliefMapTextureTile(mesh: FlightTerrainMesh, baseZoom: Int): TerrainTileId {
    val id = mesh.tileId
    val delta = (id.zoom - baseZoom).coerceAtLeast(0)
    return TerrainTileId(id.zoom - delta, id.x shr delta, id.y shr delta)
}

internal fun flightReliefMapTextureTiles(
    scene: FlightTerrainScene,
    pose: FlightReliefMapPose,
    aspectRatio: Float,
): List<TerrainTileId> {
    val safe = pose.clamped()
    val aspect = aspectRatio.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.25f, 4f) ?: 1f
    val radius = safe.widthMeters * max(1.0, 1.0 / aspect) / 1000.0
    return scene.meshes
        .filter { it.terrainAvailable }
        .map { flightReliefMapTextureTile(it, scene.zoom) }
        .distinct()
        .map { tile ->
            val latitude = FlightTerrainTilePlanner.tileYToLatitude(tile.y + 0.5, tile.zoom)
            val longitude = FlightTerrainTilePlanner.tileXToLongitude(tile.x + 0.5, tile.zoom)
            val distance =
                FlightTerrainTilePlanner.distanceKm(
                    safe.latitude,
                    safe.longitude,
                    latitude,
                    longitude,
                )
            val halfTile =
                FlightTerrainTilePlanner.distanceKm(
                    latitude,
                    longitude,
                    FlightTerrainTilePlanner.tileYToLatitude(tile.y.toDouble(), tile.zoom),
                    FlightTerrainTilePlanner.tileXToLongitude(tile.x.toDouble(), tile.zoom),
                )
            Triple(tile, distance, halfTile)
        }
        .filter { (_, distance, halfTile) -> distance <= radius + halfTile }
        .sortedBy { it.second }
        .take(32)
        .map { it.first }
}

internal fun flightReliefMapTexturedScene(
    scene: FlightTerrainScene,
    paths: Map<TerrainTileId, String>,
): FlightTerrainScene =
    scene.copy(
        meshes =
            scene.meshes.map { mesh ->
                mesh.copy(
                    nativeMapTexturePath =
                        paths[flightReliefMapTextureTile(mesh, scene.zoom)]
                            ?: mesh.nativeMapTexturePath
                )
            },
        nativeMapTiles = paths.size,
        nativeMapRequested = true,
        generation = System.nanoTime(),
    )

/** Route symbols belong on the terrain, independent of the aircraft's cruising altitude. */
internal class FlightReliefMapGround(meshes: List<FlightTerrainMesh>) {
    private val levels =
        meshes
            .filter { it.terrainAvailable }
            .groupBy { it.tileId.zoom }
            .toSortedMap(compareByDescending { it })
            .map { (zoom, tiles) -> zoom to tiles.associateBy { it.tileId } }

    fun elevation(latitude: Double, longitude: Double): Float? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        for ((zoom, tiles) in levels) {
            val id =
                TerrainTileId(
                    zoom,
                    floor(FlightTerrainTilePlanner.longitudeToTileX(longitude, zoom)).toInt(),
                    floor(FlightTerrainTilePlanner.latitudeToTileY(latitude, zoom)).toInt(),
                )
            val mesh = tiles[id] ?: continue
            FlightTerrainFloor.elevationAt(listOf(mesh), latitude, longitude)?.let {
                return it
            }
        }
        return null
    }
}
