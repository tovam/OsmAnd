package net.osmand.test.junit

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

/** All clicks, views and streamed meshes are synthetic; tests never access application data. */
class FlightPointingSelectionTest {
    private class Tasks : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }

    private fun mesh(height: Float = 0f, available: Boolean = true): FlightTerrainMesh {
        val v = FloatArray(36)
        for (y in 0..1) for (x in 0..1) {
            val i = (y * 2 + x) * 9
            v[i] = if (x == 0) -30000f else 30000f
            v[i + 1] = height
            v[i + 2] = if (y == 0) -30000f else 30000f
        }
        return FlightTerrainMesh(
            TerrainTileId(10, 0, 0),
            v,
            shortArrayOf(0, 2, 1, 1, 2, 3),
            terrainAvailable = available,
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
            meshes.count { it.terrainAvailable },
            meshes.count { !it.terrainAvailable },
            0,
            0,
            0,
            false,
            0f,
            1L,
        )

    private val pose = FlightPhotoSpatialPose(0.0, null, 0.0, 0.0, 10000f, 0f, 0f, -90f, 60f, 1f)
    private val projection = FlightPhotoProjection(pose, 1f)

    @Test
    fun earlyClicksAreQueuedAndOnlyTheLastOneIsResolvedWithoutAnotherTap() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        selection.update(scope, null, preparing = true)
        selection.select(scope, FlightPointingClick.Map(20.0, 20.0))
        selection.select(scope, FlightPointingClick.Map(0.0, 0.0))
        assertEquals(FlightPointingPhase.PREPARING, selection.state.value.phase)
        assertTrue(selection.state.value.pendingClick)
        selection.update(scope, FlightPointingContext(FlightTerrainPicker(scene(mesh()))), false)
        tasks.drain()
        assertEquals(FlightPointingPhase.READY, selection.state.value.phase)
        assertNotNull(selection.state.value.point)
        assertFalse(selection.state.value.pendingClick)
        scope.cancel()
    }

    @Test
    fun visibleLoadingPlanesCannotBecomeInventedPointsAndArrivalRetriesTheClick() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        val placeholder = FlightTerrainPicker(scene(mesh(available = false)))
        selection.update(scope, FlightPointingContext(placeholder, photo = projection), false)
        selection.select(scope, FlightPointingClick.Photo(.5, .5, projection))
        tasks.drain()
        assertEquals(FlightPointingPhase.LOADING, selection.state.value.phase)
        assertNull(selection.state.value.point)
        selection.update(
            scope,
            FlightPointingContext(
                FlightTerrainPicker(scene(mesh()), placeholder),
                photo = projection,
            ),
            false,
        )
        tasks.drain()
        assertEquals(FlightPointingPhase.READY, selection.state.value.phase)
        assertNotNull(selection.state.value.point)
        scope.cancel()
    }

    @Test
    fun missingNearTerrainDoesNotSelectKnownGroundBehindIt() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        val base = mesh()
        val incomplete = FlightTerrainPicker(scene(base, mesh(5000f, available = false)))
        selection.update(scope, FlightPointingContext(incomplete, photo = projection), false)
        selection.select(scope, FlightPointingClick.Photo(.5, .5, projection))
        tasks.drain()
        assertEquals(FlightPointingPhase.WAITING_GROUND, selection.state.value.phase)
        assertNull(selection.state.value.point)
        selection.update(
            scope,
            FlightPointingContext(
                FlightTerrainPicker(scene(base, mesh(5000f)), incomplete),
                photo = projection,
            ),
            false,
        )
        tasks.drain()
        assertEquals(5000.0, selection.state.value.point!!.altitude, .01)
        scope.cancel()
    }

    @Test
    fun supersededAndDisabledRequestsCannotPublishAnObsoleteResult() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        selection.update(scope, FlightPointingContext(FlightTerrainPicker(scene(mesh()))), false)
        selection.select(scope, FlightPointingClick.Map(20.0, 20.0))
        selection.select(scope, FlightPointingClick.Map(0.0, 0.0))
        tasks.drain()
        assertNotNull(selection.state.value.point)
        val point = selection.state.value.point
        selection.select(scope, FlightPointingClick.Map(20.0, 20.0))
        selection.reset()
        tasks.drain()
        assertEquals(point, selection.state.value.point)
        assertEquals(FlightPointingPhase.LOADING, selection.state.value.phase)
        assertFalse(selection.state.value.pendingClick)
        scope.cancel()
    }

    @Test
    fun partialGroundMissIsRetriedButTheSkyIsAnActualMiss() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        val partial = FlightTerrainPicker(scene(mesh()).copy(missingTiles = 1))
        selection.update(scope, FlightPointingContext(partial, photo = projection), false)
        selection.select(scope, FlightPointingClick.Map(.4, .4))
        tasks.drain()
        assertEquals(FlightPointingPhase.WAITING_GROUND, selection.state.value.phase)
        selection.select(scope, FlightPointingClick.Map(20.0, 20.0))
        tasks.drain()
        assertEquals(FlightPointingPhase.NO_INTERSECTION, selection.state.value.phase)
        selection.select(
            scope,
            FlightPointingClick.Photo(
                .5,
                .5,
                FlightPhotoProjection(pose.copy(viewElevationDegrees = 90f), 1f),
            ),
        )
        tasks.drain()
        assertEquals(FlightPointingPhase.NO_INTERSECTION, selection.state.value.phase)
        scope.cancel()
    }

    @Test
    fun textureOnlyUpdatesDoNotRestartASelectionOrInvalidateItsIndex() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        val original = scene(mesh())
        val picker = FlightTerrainPicker(original)
        val changedTextures =
            original.copy(
                geometryGeneration = 123L,
                meshes = original.meshes.map { it.copy(satelliteTexturePath = "synthetic-texture") },
            )
        val reused = FlightTerrainPicker(changedTextures, picker)
        assertEquals(picker.geometryKey, reused.geometryKey)
        assertEquals(0, reused.newlyIndexedMeshCount)
        selection.update(scope, FlightPointingContext(picker), false)
        selection.select(scope, FlightPointingClick.Map(0.0, 0.0))
        selection.update(scope, FlightPointingContext(reused), false)
        tasks.drain()
        assertEquals(FlightPointingPhase.READY, selection.state.value.phase)
        assertNotNull(selection.state.value.point)
        scope.cancel()
    }

    @Test
    fun newTilesReusePreviousBoundsAndAvailabilityChangesInvalidateReadiness() {
        val base = mesh()
        val original = FlightTerrainPicker(scene(base))
        val expanded = FlightTerrainPicker(scene(base, mesh(5000f)), original)
        assertEquals(1, expanded.newlyIndexedMeshCount)
        val unavailable = FlightTerrainPicker(scene(base.copy(terrainAvailable = false)), original)
        assertNotEquals(original.geometryKey, unavailable.geometryKey)
        assertFalse(unavailable.hasTerrain)
        val shifted = FlightTerrainPicker(scene(base).copy(coordinateOriginLatitude = .1), original)
        assertNotEquals(original.geometryKey, shifted.geometryKey)
    }

    @Test
    fun failedClickAutomaticallyResolvesWhenNewGroundArrives() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        val base = mesh()
        selection.update(scope, FlightPointingContext(FlightTerrainPicker(scene(base))), false)
        selection.select(scope, FlightPointingClick.Map(.4, .4))
        tasks.drain()
        assertEquals(FlightPointingPhase.NO_INTERSECTION, selection.state.value.phase)
        val larger = base.copy(vertices = base.vertices.map { it * 3 }.toFloatArray())
        selection.update(scope, FlightPointingContext(FlightTerrainPicker(scene(larger))), false)
        tasks.drain()
        assertEquals(FlightPointingPhase.READY, selection.state.value.phase)
        assertNotNull(selection.state.value.point)
        scope.cancel()
    }

    private fun frame(scene: FlightTerrainScene, east: Float = 0f): FlightPickingFrame {
        val inverse =
            floatArrayOf(
                1000f,
                0f,
                0f,
                0f,
                0f,
                0f,
                -1000f,
                0f,
                0f,
                -1000f,
                0f,
                0f,
                east,
                8000f,
                0f,
                1f,
            )
        val matrix =
            floatArrayOf(
                .001f,
                0f,
                0f,
                0f,
                0f,
                0f,
                -.001f,
                0f,
                0f,
                -.001f,
                0f,
                0f,
                -east * .001f,
                0f,
                8f,
                1f,
            )
        return FlightPickingFrame(
            scene,
            doubleArrayOf(east.toDouble(), 10000.0, 0.0),
            matrix,
            inverse,
        )
    }

    @Test
    fun waitingSceneClickKeepsItsOriginalCameraWhenTheViewMoves() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        val ground = scene(mesh())
        selection.update(scope, null, preparing = true)
        selection.select(scope, FlightPointingClick.Scene(.5, .5, frame(ground)))
        selection.update(
            scope,
            FlightPointingContext(FlightTerrainPicker(ground), frame(ground, 5000f)),
            false,
        )
        tasks.drain()
        assertEquals(0.0, selection.state.value.point!!.longitude, .00001)
        assertEquals(0.0, selection.state.value.point!!.latitude, .00001)
        scope.cancel()
    }

    @Test
    fun cameraMovementClearsAnObsoleteSkyMissWithoutErasingTheSelectedPoint() {
        val tasks = Tasks()
        val scope = CoroutineScope(SupervisorJob() + tasks)
        val selection = FlightPointingSelection(tasks)
        val ground = scene(mesh())
        val picker = FlightTerrainPicker(ground)
        selection.update(scope, FlightPointingContext(picker, frame(ground)), false)
        selection.select(scope, FlightPointingClick.Map(0.0, 0.0))
        tasks.drain()
        val point = selection.state.value.point
        selection.select(scope, FlightPointingClick.Map(20.0, 20.0))
        tasks.drain()
        assertEquals(FlightPointingPhase.NO_INTERSECTION, selection.state.value.phase)
        selection.update(scope, FlightPointingContext(picker, frame(ground, 5000f)), false)
        assertEquals(FlightPointingPhase.READY, selection.state.value.phase)
        assertEquals(point, selection.state.value.point)
        scope.cancel()
    }

    @Test
    fun blockAccelerationFindsEdgesAndTrianglesWithUnsignedVertexIndices() {
        val quads = 255
        val width = quads + 1
        val vertices = FloatArray(width * width * 9)
        for (row in 0..quads) for (col in 0..quads) {
            val at = (row * width + col) * 9
            val x = -1000f + col * (2000f / quads)
            val z = -1000f + row * (2000f / quads)
            vertices[at] = x
            vertices[at + 1] = 1000f + .1f * x + .02f * z
            vertices[at + 2] = z
        }
        val indices = ShortArray(quads * quads * 6)
        for (row in 0 until quads) for (col in 0 until quads) {
            val at = (row * quads + col) * 6
            val v = row * width + col
            listOf(v, v + width, v + 1, v + 1, v + width, v + width + 1).forEachIndexed { i, n ->
                indices[at + i] = n.toShort()
            }
        }
        val picker =
            FlightTerrainPicker(
                scene(FlightTerrainMesh(TerrainTileId(10, 0, 0), vertices, indices))
            )
        for (x in listOf(-999.0, -600.0, 0.0, 900.0, 999.0)) for (z in listOf(-999.0, 0.0, 999.0)) {
            val ray = FlightPickingRay(doubleArrayOf(x, 10000.0, z), doubleArrayOf(0.0, -1.0, 0.0))
            assertEquals(9000.0 - .1 * x - .02 * z, picker.hit(ray)!!, .02)
        }
        assertNull(
            picker.hit(
                FlightPickingRay(doubleArrayOf(1001.0, 10000.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0))
            )
        )
    }
}
