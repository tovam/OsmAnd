package net.osmand.plus.plugins.flightmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.osmand.plus.OsmandApplication

/** Shared decoded backdrop, in addition to the persistent satellite files in the journey store. */
internal object FlightPhotoTileCache {
    private val bitmaps =
        object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private var terrain: FlightTerrainRepository? = null
    private var native: FlightNativeMapTextureRepository? = null

    fun key(satellite: Boolean, tile: TerrainTileId) = "$satellite/${tile.zoom}/${tile.x}/${tile.y}"

    fun get(satellite: Boolean, tile: TerrainTileId): Bitmap? = bitmaps.get(key(satellite, tile))

    fun put(satellite: Boolean, tile: TerrainTileId, bitmap: Bitmap) {
        // Never recycle an evicted bitmap: a Canvas may still own that frame.
        bitmaps.put(key(satellite, tile), bitmap)
    }

    suspend fun load(app: OsmandApplication, satellite: Boolean, tile: TerrainTileId): Bitmap {
        get(satellite, tile)?.let {
            return it
        }
        // Called on Main; repository IO/decoding runs off Main. Sharing also shares file locks.
        val path =
            if (satellite) {
                val repository = terrain ?: FlightTerrainRepository(app).also { terrain = it }
                repository.calibrationSatellite(tile)
            } else {
                val repository =
                    native ?: FlightNativeMapTextureRepository(app).also { native = it }
                repository.renderTextures(listOf(tile)) { _, _, _ -> }.texturePaths[tile]
                    ?: throw IOException("Missing map texture")
            }
        return withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sampling = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampling > 256) sampling *= 2
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sampling
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            ) ?: throw IOException("Unreadable map texture")
        }
    }
}
