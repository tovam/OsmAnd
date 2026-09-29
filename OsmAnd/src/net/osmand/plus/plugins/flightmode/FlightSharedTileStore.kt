package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One catalog for both source roots, shared by every flight and every repository instance. */
internal class FlightSharedTileStore private constructor(private val root: File) {
    val catalog = FlightTileCatalog()
    private val scanMutex = Mutex()
    private val scannedDirectories = HashSet<String>()
    // Application lifetime: only metadata and a filesDir path are retained, never an Activity.
    @Suppress("unused")
    private val stopChanges =
        FlightOfflineTileChanges.observe { key, bytes -> catalog.changed(key, bytes) }

    fun file(key: FlightOfflineTileKey): File =
        File(
            root,
            "${if (key.satellite) FlightSatelliteSource.CACHE_DIRECTORY else FlightTerrainRepository.TERRAIN_DIRECTORY}/" +
                "${key.tile.zoom}/${key.tile.x}/${key.tile.y}.${if (key.satellite) "jpg" else "png"}",
        )

    /**
     * One cancellable disk inventory per process. Missing files never require a stat per flight.
     */
    suspend fun ensureIndexed() =
        withContext(Dispatchers.IO) {
            scanMutex.withLock {
                if (catalog.summary().complete) return@withLock
                // Publish coarse backdrops for both sources before inspecting fine levels.
                // Directory enumeration order must not keep the first map blank behind z14.
                for (z in 0..22) {
                    for (satellite in listOf(true, false)) {
                        val directory =
                            File(
                                root,
                                if (satellite) FlightSatelliteSource.CACHE_DIRECTORY
                                else FlightTerrainRepository.TERRAIN_DIRECTORY,
                            )
                        currentCoroutineContext().ensureActive()
                        val zoomDirectory = File(directory, z.toString())
                        if (!zoomDirectory.exists()) continue
                        if (!zoomDirectory.isDirectory) throw IOException("Invalid tile level z$z")
                        val columns =
                            zoomDirectory.listFiles()
                                ?: throw IOException("Cannot inventory tile level z$z")
                        for (column in columns) {
                            currentCoroutineContext().ensureActive()
                            val x =
                                column.name.toIntOrNull()?.takeIf { it in 0 until (1 shl z) }
                                    ?: continue
                            if (!column.isDirectory || column.path in scannedDirectories) continue
                            val sourceFiles =
                                column.listFiles()
                                    ?: throw IOException("Cannot inventory tile column")
                            val extension = if (satellite) "jpg" else "png"
                            for (source in sourceFiles) {
                                currentCoroutineContext().ensureActive()
                                if (source.extension != extension) continue
                                val y =
                                    source.nameWithoutExtension.toIntOrNull()?.takeIf {
                                        it in 0 until (1 shl z)
                                    } ?: continue
                                val key = FlightOfflineTileKey(TerrainTileId(z, x, y), satellite)
                                val revision = catalog.revision(key)
                                // Every managed write already publishes a validated source-file
                                // event.
                                if (revision != 0L) continue
                                val options =
                                    BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(source.path, options)
                                val bytes =
                                    if (options.outWidth == 256 && options.outHeight == 256)
                                        source.length()
                                    else 0L
                                catalog.inspected(key, revision, bytes)
                            }
                            scannedDirectories.add(column.path)
                        }
                    }
                }
                catalog.finishInventory()
                scannedDirectories.clear()
            }
        }

    companion object {
        @Volatile private var instance: FlightSharedTileStore? = null

        fun get(context: Context): FlightSharedTileStore =
            instance
                ?: synchronized(this) {
                    instance
                        ?: FlightSharedTileStore(context.applicationContext.filesDir).also {
                            instance = it
                        }
                }
    }
}
