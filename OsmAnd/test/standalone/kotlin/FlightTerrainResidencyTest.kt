package net.osmand.test.junit

import net.osmand.plus.plugins.flightmode.*
import org.junit.Assert.*
import org.junit.Test

class FlightTerrainResidencyTest {
    @Test fun aLoadingPlaneNeverPoisonsTheRealElevationGeometryCache() {
        val tile = TerrainTileId(8, 128, 128)
        val plan = TerrainTilePlan(8, listOf(tile))
        val cache = linkedMapOf<FlightTerrainGeometryCacheKey, FlightTerrainGeometry>()
        val loading = FlightTerrainMeshBuilder.build(0.0, 0.0, 100, plan, emptyMap(),
            geometryCache = cache, includePlaceholders = true).meshes.single()
        assertFalse(loading.terrainAvailable)
        val loaded = FlightTerrainMeshBuilder.build(0.0, 0.0, 100, plan,
            mapOf(tile to terrain(tile, 1_234f)), geometryCache = cache, includePlaceholders = true).meshes.single()
        assertTrue(loaded.terrainAvailable)
        assertEquals(1_234f, loaded.minimumElevationMeters, 0.01f)
        assertEquals(1_234f, loaded.maximumElevationMeters, 0.01f)
        assertEquals((loaded.gridQuads + 1) * (loaded.gridQuads + 1) * FlightTerrainMeshBuilder.VERTEX_COMPONENTS, loaded.vertices.size)
        assertTrue(FlightTerrainMeshBuilder.build(0.0, 0.0, 100, plan, emptyMap(), geometryCache = cache).meshes.isEmpty())
    }

    @Test fun repeatedRetargetingDoesNotAccumulateOldProgressiveScenes() {
        var meshes = emptyList<FlightTerrainMesh>()
        repeat(500) { step ->
            val tile = TerrainTileId(10, step, 512)
            val neighbor = TerrainTileId(10, step + 1, 512)
            meshes = FlightTerrainResidency.retainPlanned(meshes, setOf(tile to 0, neighbor to 0))
            if (meshes.none { it.tileId == neighbor }) {
                meshes = meshes + FlightTerrainMesh(neighbor, FloatArray(0), ShortArray(0))
            }
            assertTrue("Retarget $step retained ${meshes.size} meshes", meshes.size <= 2)
            assertTrue(meshes.all { it.tileId == tile || it.tileId == neighbor })
        }
    }

    @Test fun retainedMeshKeepsItsTextureAndGeometryWithoutRebuilding() {
        val tile = TerrainTileId(10, 512, 512)
        val mesh = FlightTerrainMesh(tile, floatArrayOf(1f), shortArrayOf(0), satelliteTextureTier = FlightTerrainTextureTier.ULTRA_PLUS_PLUS_PLUS)
        assertSame(mesh, FlightTerrainResidency.retainPlanned(listOf(mesh), setOf(tile to 0)).single())
        assertTrue(FlightTerrainResidency.retainPlanned(listOf(mesh), setOf(tile to 1)).isEmpty())
    }

    @Test fun refinementWaitsForItsOwnParentNotAnUnrelatedLoadedTile() {
        val child = TerrainTileId(10, 515, 515)
        val unrelated = TerrainTileId(9, 258, 257)
        assertTrue(refine(child, emptyMap()).isEmpty())
        assertTrue(refine(child, mapOf(unrelated to terrain(unrelated, 1_000f))).isEmpty())
        val parent = TerrainTileId(9, 257, 257)
        assertEquals(1, refine(child, mapOf(parent to terrain(parent, 1_000f))).size)
    }

    @Test fun everyExposedEdgeIncludingSouthAndEastMatchesTheParent() {
        // Last child in the parent: its east/south border lies just outside that parent's tile ID.
        val child = TerrainTileId(10, 515, 515)
        val parent = TerrainTileId(9, 257, 257)
        val mesh = refine(child, mapOf(parent to terrain(parent, 1_000f))).single()
        val size = mesh.gridQuads + 1
        for (row in 0 until size) for (column in 0 until size) {
            val elevation = mesh.vertices[(row * size + column) * FlightTerrainMeshBuilder.VERTEX_COMPONENTS + 6]
            if (row == 0 || column == 0 || row == size - 1 || column == size - 1) {
                assertEquals("Edge $row,$column", 1_000f, elevation, 0.01f)
            }
        }
        val middle = (size / 2 * size + size / 2) * FlightTerrainMeshBuilder.VERTEX_COMPONENTS + 6
        assertEquals(2_000f, mesh.vertices[middle], 0.01f)
        assertTrue(mesh.vertices.all { it.isFinite() })
        assertEquals(mesh.gridQuads * mesh.gridQuads * 6, mesh.indices.size)
    }

    @Test fun parentResolutionIsExactAndRejectsInvalidZooms() {
        val child = TerrainTileId(14, 8199, 8201)
        assertEquals(TerrainTileId(12, 2049, 2050), FlightTerrainResidency.parent(child, 12))
        assertNull(FlightTerrainResidency.parent(child, 15))
        assertNull(FlightTerrainResidency.parent(child, -1))
    }

    private fun terrain(id: TerrainTileId, elevation: Float) =
        TerrariumTile(id, 256, 256, FloatArray(256 * 256) { elevation })

    private fun refine(child: TerrainTileId, boundary: Map<TerrainTileId, TerrariumTile>) =
        FlightTerrainMeshBuilder.buildRefinementMeshes(
            baseZoom = 8, plan = TerrainTilePlan(child.zoom, listOf(child)),
            tiles = mapOf(child to terrain(child, 2_000f)), boundaryZoom = 9, boundaryTiles = boundary,
            baseSatelliteTexturePaths = emptyMap(), baseStandardSatelliteTexturePaths = emptyMap(),
            baseSatelliteTextureTiers = emptyMap(), coordinateOriginLatitude = 0.0,
            coordinateOriginLongitude = 0.0, geometryQuadsByTile = emptyMap(), geometryCache = null,
        )
}
