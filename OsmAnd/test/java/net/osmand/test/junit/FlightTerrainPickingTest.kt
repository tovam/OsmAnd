package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic geometry only; no application data, terrain files or user photos. */
class FlightTerrainPickingTest {
    private fun ground(height: Float = 0f): FlightTerrainMesh {
        val vertices = FloatArray(36)
        for (y in 0..1) for (x in 0..1) {
            val i = (y * 2 + x) * 9
            vertices[i] = if (x == 0) -30000f else 30000f
            vertices[i + 1] = height
            vertices[i + 2] = if (y == 0) -30000f else 30000f
            vertices[i + 6] = height
        }
        return FlightTerrainMesh(
            TerrainTileId(10, 0, 0),
            vertices,
            shortArrayOf(0, 2, 1, 1, 2, 3),
            gridQuads = 1,
        )
    }

    private fun scene(vararg meshes: FlightTerrainMesh) =
        FlightTerrainScene(
            0.0,
            0.0,
            null,
            0.0,
            0.0,
            100,
            10,
            12,
            11,
            FlightSatelliteQuality.STANDARD,
            meshes.toList(),
            meshes.size,
            0,
            0,
            0,
            0,
            false,
            0f,
            1L,
        )

    private val pose = FlightPhotoSpatialPose(0.0, null, 0.0, 0.0, 10000f, 0f, 0f, -90f, 60f, 1f)

    @Test
    fun nearestTerrainWinsInEitherMeshOrderAndHiddenPointIsHalfOpaque() {
        val ray =
            FlightPickingRay.through(
                doubleArrayOf(0.0, 10000.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0),
            )!!
        for (meshes in listOf(arrayOf(ground(), ground(5000f)), arrayOf(ground(5000f), ground()))) {
            val picker = FlightTerrainPicker(scene(*meshes))
            assertEquals(5000.0, picker.hit(ray)!!, .001)
            assertEquals(.5f, picker.opacity(ray.eye, doubleArrayOf(0.0, 0.0, 0.0)), 0f)
            assertEquals(1f, picker.opacity(ray.eye, doubleArrayOf(0.0, 5000.0, 0.0)), 0f)
        }
    }

    @Test
    fun missingTerrainSkyAndGeometryBehindTheEyeDoNotInventPoints() {
        val picker = FlightTerrainPicker(scene(ground().copy(terrainAvailable = false)))
        assertNull(picker.groundAt(0.0, 0.0))
        val actual = FlightTerrainPicker(scene(ground()))
        assertNull(
            actual.hit(
                FlightPickingRay.through(
                    doubleArrayOf(0.0, 10000.0, 0.0),
                    doubleArrayOf(0.0, 20000.0, 0.0),
                )!!
            )
        )
        assertNull(actual.groundAt(20.0, 20.0))
        assertNull(
            FlightPhotoPicker(
                    FlightPhotoProjection(pose.copy(viewElevationDegrees = 90f), 1f),
                    actual,
                )
                .pointAt(.5, .5)
        )
    }

    @Test
    fun photoToMapToPhotoRoundTripsIncludingOpticalRotationAndOffsets() {
        val terrain = FlightTerrainPicker(scene(ground()))
        for (projection in
            listOf(
                FlightPhotoProjection(pose, 1.5f),
                FlightPhotoProjection(
                    pose,
                    1.5f,
                    scale = .8f,
                    offsetX = .1f,
                    offsetY = -.2f,
                    rotation = 37f,
                ),
            )) {
            val picker = FlightPhotoPicker(projection, terrain)
            for ((x, y) in listOf(.5 to .5, .2 to .3, .8 to .7)) {
                val point = picker.pointAt(x, y)!!
                val marker = picker.project(point)
                assertEquals(x, marker.x!!, .00002)
                assertEquals(y, marker.y!!, .00002)
                assertEquals(1f, marker.opacity, 0f)
                assertNotNull(terrain.groundAt(point.latitude, point.longitude))
            }
            assertNull(picker.pointAt(-.1, .5))
        }
    }

    @Test
    fun photoCanProjectACartographicPointBehindRelief() {
        val clear = FlightTerrainPicker(scene(ground()))
        val projection = FlightPhotoProjection(pose, 1f)
        val point = FlightPhotoPicker(projection, clear).pointAt(.5, .5)!!
        val blocked =
            FlightPhotoPicker(projection, FlightTerrainPicker(scene(ground(5000f), ground())))
        val marker = blocked.project(point)
        assertEquals(.5, marker.x!!, .00001)
        assertEquals(.5, marker.y!!, .00001)
        assertEquals(.5f, marker.opacity, 0f)
        assertEquals(5000.0, blocked.pointAt(.5, .5)!!.altitude, .1)
    }

    @Test
    fun geographicPointSurvivesACurvedEarthOriginChange() {
        val base = FlightTerrainCoordinates(0.0, 0.0)
        val shifted = FlightTerrainCoordinates(.1, .1)
        val mesh = ground()
        val vertices = mesh.vertices.clone()
        for (i in 0..3) {
            val geo = base.toGeographic(DoubleArray(3) { vertices[i * 9 + it].toDouble() })
            val local = shifted.toLocal(geo[0], geo[1], geo[2])
            for (a in 0..2) vertices[i * 9 + a] = local[a]
        }
        val projection = FlightPhotoProjection(pose, 1f)
        val a = FlightPhotoPicker(projection, FlightTerrainPicker(scene(mesh))).pointAt(.4, .6)!!
        val b =
            FlightPhotoPicker(
                    projection,
                    FlightTerrainPicker(
                        scene(mesh.copy(vertices = vertices))
                            .copy(coordinateOriginLatitude = .1, coordinateOriginLongitude = .1)
                    ),
                )
                .pointAt(.4, .6)!!
        assertEquals(a.latitude, b.latitude, .000001)
        assertEquals(a.longitude, b.longitude, .000001)
        assertEquals(a.altitude, b.altitude, .05)
    }

    @Test
    fun signedShortIndicesAreTreatedAsUnsigned() {
        val mesh = ground()
        val vertices = FloatArray(65536 * 9)
        mesh.vertices.copyInto(vertices, 65532 * 9)
        val large = mesh.copy(vertices = vertices, indices = shortArrayOf(-4, -2, -3, -3, -2, -1))
        val picker = FlightTerrainPicker(scene(large))
        assertEquals(
            10000.0,
            picker.hit(
                FlightPickingRay.through(
                    doubleArrayOf(0.0, 10000.0, 0.0),
                    doubleArrayOf(0.0, 0.0, 0.0),
                )!!
            )!!,
            .001,
        )
    }

    @Test
    fun actualCameraFrameProjectsAndUnprojectsWithoutSelectingBehindCamera() {
        // Perspective camera at (0, 0, 10), looking along -Z, near=1, far=100.
        val matrix =
            floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                0f,
                1f,
                0f,
                0f,
                0f,
                0f,
                -101f / 99,
                -1f,
                0f,
                0f,
                810f / 99,
                10f,
            )
        val inverse = invert(matrix)
        val frame = FlightPickingFrame(scene(), doubleArrayOf(0.0, 0.0, 10.0), matrix, inverse)
        val point = doubleArrayOf(2.0, 1.0, 0.0)
        val pixel = frame.project(point)!!
        assertEquals(.6, pixel.first, .00001)
        assertEquals(.45, pixel.second, .00001)
        val ray = frame.ray(pixel.first, pixel.second)!!
        val t = (point[2] - ray.eye[2]) / ray.direction[2]
        for (i in 0..2) assertEquals(point[i], ray.eye[i] + t * ray.direction[i], .0001)
        assertNull(frame.project(doubleArrayOf(0.0, 0.0, 11.0)))
        assertNull(frame.project(doubleArrayOf(200.0, 0.0, 0.0)))
    }

    private fun invert(matrix: FloatArray): FloatArray {
        val rows =
            Array(4) { r ->
                DoubleArray(8) { c ->
                    if (c < 4) matrix[c * 4 + r].toDouble() else if (c - 4 == r) 1.0 else 0.0
                }
            }
        for (i in 0..3) {
            val pivot = rows[i][i]
            for (c in 0..7) rows[i][c] /= pivot
            for (r in 0..3) if (r != i) {
                val ratio = rows[r][i]
                for (c in 0..7) rows[r][c] -= ratio * rows[i][c]
            }
        }
        return FloatArray(16) { rows[it % 4][4 + it / 4].toFloat() }
    }
}
