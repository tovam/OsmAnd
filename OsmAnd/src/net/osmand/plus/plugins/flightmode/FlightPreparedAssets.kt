package net.osmand.plus.plugins.flightmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import net.osmand.plus.media.MediaMetadataUtils
import net.osmand.util.PreparedResourceQueue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max

/** CPU-only preparation. No file reads, decoding or buffer copies on the GL thread. */
internal class FlightPreparedAssets(changed: () -> Unit) : AutoCloseable {
	sealed interface Key
	// Arrays intentionally use identity equality: new geometry is a different revision.
	class GeometryKey(val vertices: FloatArray, val indices: ShortArray) : Key {
		override fun equals(other: Any?): Boolean = other is GeometryKey &&
			vertices === other.vertices && indices === other.indices
		override fun hashCode(): Int = 31 * System.identityHashCode(vertices) + System.identityHashCode(indices)
	}
	data class ImageKey(val path: String, val edge: Int, val photo: Boolean = false,
		val dehaze: FlightPhotoDehaze.Recipe = FlightPhotoDehaze.Recipe()) : Key
	sealed interface Asset
	data class Geometry(val vertices: FloatBuffer, val indices: ShortBuffer) : Asset
	data class Image(val bitmap: Bitmap) : Asset
	private val queue = PreparedResourceQueue<Key, Asset>(2, STAGING_BYTES, { asset ->
		if (asset is Image) asset.bitmap.recycle()
	}, changed)
	val closed: Boolean get() = queue.isClosed
	val bytes: Long get() = queue.reservedBytes()
	val pending: Int get() = queue.pendingCount()
	val failures: Long get() = queue.failureCount()

	fun geometryRequest(mesh: FlightTerrainMesh): PreparedResourceQueue.Request<Key, Asset> {
		val key = GeometryKey(mesh.vertices, mesh.indices)
		return PreparedResourceQueue.Request(key, mesh.vertices.size * 4L + mesh.indices.size * 2L) {
			require(mesh.vertices.size % 9 == 0 && mesh.vertices.all(Float::isFinite))
			require(mesh.indices.all { (it.toInt() and 0xffff) < mesh.vertices.size / 9 })
			Geometry(
				ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
					.apply { put(mesh.vertices).position(0) },
				ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
					.apply { put(mesh.indices).position(0) }
			)
		}
	}

	fun imageRequest(key: ImageKey): PreparedResourceQueue.Request<Key, Asset> =
		PreparedResourceQueue.Request(key, key.edge.toLong() * key.edge * if (key.dehaze.amount > 0) 24L else if (key.photo) 8L else 2L) {
			val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(key.path, bounds)
			require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image dimensions" }
			var sample = 1
			while (max(bounds.outWidth, bounds.outHeight) / sample > key.edge) sample *= 2
			val decoded = BitmapFactory.decodeFile(key.path, BitmapFactory.Options().apply {
				inSampleSize = sample
				inPreferredConfig = if (key.photo) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
			}) ?: error("Image decoding failed")
			try {
				val orientation = if (key.photo) MediaMetadataUtils.getExifOrientation(File(key.path)) else 1
				val angle = when (orientation) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
				val oriented = if (angle == 0f) decoded else Bitmap.createBitmap(
					decoded, 0, 0, decoded.width, decoded.height,
					android.graphics.Matrix().apply { postRotate(angle) }, true
				)
				if (oriented !== decoded) decoded.recycle()
				val processed = try { FlightPhotoDehaze.apply(oriented, key.dehaze) }
					catch (failure: Throwable) { if (oriented !== decoded) oriented.recycle(); throw failure }
				if (processed !== oriented) oriented.recycle()
				Image(processed)
			} catch (error: Throwable) {
				decoded.recycle()
				throw error
			}
		}

	fun reconcile(requests: List<PreparedResourceQueue.Request<Key, Asset>>) = queue.reconcile(requests)
	fun takeGeometry(mesh: FlightTerrainMesh): Geometry? = queue.take(GeometryKey(mesh.vertices, mesh.indices)) as? Geometry
	fun takeImage(key: ImageKey): Image? = queue.take(key) as? Image
	fun isReady(key: Key): Boolean = queue.isReady(key)
	override fun close() = queue.close()

	companion object {
		// At most one 4096px oriented photo or one 8192px RGB565 source composite.
		const val STAGING_BYTES = 144L * 1024L * 1024L
	}
}
