package net.osmand.plus.plugins.flightmode

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.atan
import kotlin.math.sqrt

/** Reads only optical metadata needed to start a photo/terrain calibration. */
object FlightPhotoPerspective {

	fun detectVerticalFieldOfViewDegrees(file: File): Float? = runCatching {
		if (!file.isFile) return@runCatching null
		val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.absolutePath, bounds)
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
		val exif = ExifInterface(file.absolutePath)
		val focalLength35mm = exif.getAttributeDouble(
			ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
			Double.NaN
		)
		if (!focalLength35mm.isFinite() || focalLength35mm <= 0.0) return@runCatching null
		val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
		val rotatedQuarterTurn = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
			orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
			orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
			orientation == ExifInterface.ORIENTATION_TRANSVERSE
		val displayedWidth = if (rotatedQuarterTurn) bounds.outHeight else bounds.outWidth
		val displayedHeight = if (rotatedQuarterTurn) bounds.outWidth else bounds.outHeight
		verticalFieldOfViewFrom35mm(focalLength35mm, displayedWidth, displayedHeight)
	}.getOrNull()

	fun verticalFieldOfViewFrom35mm(
		focalLength35mm: Double,
		displayedWidthPixels: Int,
		displayedHeightPixels: Int
	): Float? {
		if (!focalLength35mm.isFinite() || focalLength35mm <= 0.0 ||
			displayedWidthPixels <= 0 || displayedHeightPixels <= 0
		) return null
		val aspect = displayedWidthPixels.toDouble() / displayedHeightPixels
		val fullFrameDiagonalMm = sqrt(FULL_FRAME_WIDTH_MM * FULL_FRAME_WIDTH_MM +
			FULL_FRAME_HEIGHT_MM * FULL_FRAME_HEIGHT_MM)
		val equivalentSensorHeightMm = fullFrameDiagonalMm / sqrt(aspect * aspect + 1.0)
		val verticalFov = Math.toDegrees(2.0 * atan(equivalentSensorHeightMm / (2.0 * focalLength35mm)))
		return verticalFov.toFloat().takeIf(Float::isFinite)?.coerceIn(
			FlightWindowPlacement.MIN_VERTICAL_FIELD_OF_VIEW_DEGREES,
			FlightWindowPlacement.MAX_VERTICAL_FIELD_OF_VIEW_DEGREES
		)
	}

	fun windowZoomForVerticalFieldOfView(verticalFieldOfViewDegrees: Float): Float =
		(FlightWindowPlacement.DEFAULT_VERTICAL_FIELD_OF_VIEW_DEGREES /
			verticalFieldOfViewDegrees.coerceIn(
				FlightWindowPlacement.MIN_VERTICAL_FIELD_OF_VIEW_DEGREES,
				FlightWindowPlacement.MAX_VERTICAL_FIELD_OF_VIEW_DEGREES
			)).coerceIn(FlightWindowPlacement.MIN_ZOOM, FlightWindowPlacement.MAX_ZOOM)

	private const val FULL_FRAME_WIDTH_MM = 36.0
	private const val FULL_FRAME_HEIGHT_MM = 24.0
}
