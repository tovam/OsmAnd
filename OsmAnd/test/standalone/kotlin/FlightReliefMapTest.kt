package net.osmand.test.junit

import kotlin.math.*
import net.osmand.plus.plugins.flightmode.*
import net.osmand.util.DirectionalViewMatrix
import org.junit.Assert.*
import org.junit.Test

/** Synthetic geography only, including polar and date-line camera configurations. */
class FlightReliefMapTest {
    @Test
    fun tiltedAndVerticalCamerasAimAtTheSameGeographicCentre() {
        for (latitude in listOf(-80.0, 0.0, 80.0)) for (longitude in
            listOf(-179.99, 0.0, 179.99)) for (heading in listOf(0f, 90f, 180f, 270f)) for (tilt in
            listOf(0f, 35f, 50f)) {
            val pose = FlightReliefMapPose(latitude, longitude, 50_000.0, heading, tilt)
            val camera = pose.camera(1.5f, 2500f)
            val coordinates = FlightTerrainCoordinates(latitude, longitude)
            val eye =
                coordinates.toLocal(
                    camera.eyeLatitude,
                    camera.eyeLongitude,
                    camera.eyeAltitudeMeters.toDouble(),
                )
            val direction = camera.direction(coordinates, eye)
            val up = camera.up(coordinates)
            assertTrue(eye.all { it.isFinite() })
            assertTrue(camera.eyeAltitudeMeters > 2500f)
            val length = sqrt(direction.sumOf { (it * it).toDouble() })
            assertEquals(
                0.0,
                direction.indices.sumOf { direction[it] * up[it].toDouble() } / length,
                0.00001,
            )
            // The exact vertical camera must have a non-degenerate basis too.
            val view = DirectionalViewMatrix.create(eye, direction, up)
            val target = coordinates.toLocal(latitude, longitude, 2500.0)
            fun component(row: Int) =
                view[row] * target[0] +
                    view[4 + row] * target[1] +
                    view[8 + row] * target[2] +
                    view[12 + row]
            assertEquals(0.0, component(0).toDouble(), 0.02)
            assertEquals(0.0, component(1).toDouble(), 0.02)
            assertTrue(component(2) < 0f)
        }
    }

    @Test
    fun zoomPreservesTheCentreAndPanFollowsTheRotatedScreenAxes() {
        val initial = FlightReliefMapPose(0.0, 0.0, 20_000.0, tiltDegrees = 0f)
        val zoomed = initial.transform(0f, 0f, 2f, 0f, 1000)
        assertEquals(initial.latitude, zoomed.latitude, 0.0)
        assertEquals(initial.longitude, zoomed.longitude, 0.0)
        assertEquals(10_000.0, zoomed.widthMeters, 0.0)
        val west = initial.transform(100f, 0f, 1f, 0f, 1000)
        assertTrue(west.longitude < 0)
        assertEquals(0.0, west.latitude, 0.000001)
        val north = initial.copy(headingDegrees = 90f).transform(100f, 0f, 1f, 0f, 1000)
        assertTrue(north.latitude > 0)
        assertEquals(0.0, north.longitude, 0.000001)
        val tilted = initial.copy(tiltDegrees = 35f).transform(0f, 100f, 1f, 0f, 1000)
        assertTrue(tilted.latitude > initial.transform(0f, 100f, 1f, 0f, 1000).latitude)
    }

    @Test
    fun gesturesCrossTheDateLineAndRejectInvalidInputWithoutBreakingTheCamera() {
        val pose = FlightReliefMapPose(30.0, 179.999, 20_000.0)
        val moved = pose.transform(-100f, 0f, 1f, 0f, 1000)
        assertTrue(moved.longitude < -179.0)
        assertEquals(pose, pose.transform(Float.NaN, 1f, 1f, 0f, 1000))
        assertEquals(pose, pose.transform(0f, 0f, 0f, 0f, 1000))
        assertEquals(pose, pose.transform(1f, 1f, 1f, 0f, 0))
        assertEquals(500.0, pose.transform(0f, 0f, 1000f, 0f, 1000).widthMeters, 0.0)
        assertEquals(600_000.0, pose.transform(0f, 0f, .0001f, 0f, 1000).widthMeters, 0.0)
        assertEquals(80.0, pose.copy(longitude = -1000.0).clamped().longitude, 0.0)
        val sanitized =
            FlightReliefMapPose(
                    Double.NaN,
                    Double.POSITIVE_INFINITY,
                    Double.NaN,
                    Float.NaN,
                    Float.NaN,
                )
                .clamped()
        assertTrue(sanitized.camera(Float.NaN, Float.NaN).eyeAltitudeMeters.isFinite())
    }

    @Test
    fun baseAndRefinedMeshesShareTheCorrectCartographicUvTileWithoutChangingRelief() {
        val base = mesh(TerrainTileId(8, 255, 120))
        val refined = mesh(TerrainTileId(11, 2046, 962))
        val other = mesh(TerrainTileId(8, 0, 120))
        val original = scene(listOf(base, refined, other))
        assertEquals(base.tileId, flightReliefMapTextureTile(refined, 8))
        val next = flightReliefMapTexturedScene(original, mapOf(base.tileId to "synthetic-map.png"))
        assertEquals("synthetic-map.png", next.meshes[0].nativeMapTexturePath)
        assertEquals("synthetic-map.png", next.meshes[1].nativeMapTexturePath)
        assertNull(next.meshes[2].nativeMapTexturePath)
        assertNull(original.meshes[0].nativeMapTexturePath)
        next.meshes.zip(original.meshes).forEach { (a, b) ->
            assertSame(a.vertices, b.vertices)
            assertSame(a.indices, b.indices)
        }
        assertEquals(original.geometryGeneration, next.geometryGeneration)
    }

    @Test
    fun textureDemandIsBoundedAndNeverRequestsReliefOrUnloadedTiles() {
        val loaded = (0..255).flatMap { x -> (120..126).map { y -> mesh(TerrainTileId(8, x, y)) } }
        val original = scene(loaded)
        val requested =
            flightReliefMapTextureTiles(original, FlightReliefMapPose(0.0, 179.999, 600_000.0), .5f)
        assertTrue(requested.isNotEmpty())
        assertTrue(requested.size <= 32)
        assertTrue(requested.all { id -> loaded.any { it.tileId == id } })
        assertTrue(requested.any { it.x == 0 })
        assertTrue(requested.any { it.x == 255 })
        assertTrue(
            flightReliefMapTextureTiles(original, FlightReliefMapPose(-80.0, 0.0, 500.0), 1f)
                .isEmpty()
        )
    }

    @Test
    fun flatMapResumesWhenReliefIsDisabledOrAnotherMapPageOpens() {
        assertTrue(FlightWorkPolicy.nativeMapVisible(FlightPage.MIXED, false, false))
        assertFalse(FlightWorkPolicy.nativeMapVisible(FlightPage.MIXED, false, true))
        assertTrue(FlightWorkPolicy.nativeMapVisible(FlightPage.MAP, false, true))
        assertFalse(FlightWorkPolicy.nativeMapVisible(FlightPage.MAP, true, true))
        assertFalse(FlightWorkPolicy.nativeMapVisible(FlightPage.PHOTO, false, false))
    }

    @Test
    fun routeSymbolsUseTheFinestKnownGroundAndNeverCruisingAltitude() {
        fun ground(id: TerrainTileId, height: Float): FlightTerrainMesh {
            val vertices = FloatArray(36)
            for (i in 0..3) vertices[i * 9 + 6] = height
            return FlightTerrainMesh(id, vertices, shortArrayOf(0, 2, 1, 1, 2, 3), gridQuads = 1)
        }
        val base = ground(TerrainTileId(8, 128, 128), 250f)
        val refined = ground(TerrainTileId(12, 2048, 2048), 3000f)
        val sampler = FlightReliefMapGround(listOf(base, refined))
        assertEquals(3000f, sampler.elevation(0.0, 0.0)!!, 0f)
        assertEquals(250f, sampler.elevation(-.2, .2)!!, 0f)
        assertNull(sampler.elevation(80.0, 0.0))
        assertEquals(
            250f,
            FlightReliefMapGround(listOf(base, refined.copy(terrainAvailable = false)))
                .elevation(0.0, 0.0)!!,
            0f,
        )
    }

    private fun mesh(id: TerrainTileId) =
        FlightTerrainMesh(id, FloatArray(36), shortArrayOf(0, 2, 1, 1, 2, 3))

    private fun scene(meshes: List<FlightTerrainMesh>) =
        FlightTerrainScene(
            0.0,
            0.0,
            null,
            0.0,
            0.0,
            300,
            8,
            11,
            10,
            FlightSatelliteQuality.STANDARD,
            meshes,
            meshes.size,
            0,
            0,
            0,
            0,
            false,
            0f,
            1L,
        )
}
