package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import net.osmand.Location
import net.osmand.plus.shared.SharedUtil
import net.osmand.shared.gpx.GpxFile
import net.osmand.shared.gpx.primitives.WptPt
import java.io.IOException

object FlightTripLoader {

	@Throws(IOException::class)
	fun load(context: Context, uri: Uri): FlightTrip {
		val displayName = findDisplayName(context, uri) ?: "Trajet importé"
		val gpxFile = context.contentResolver.openInputStream(uri)?.use { input ->
			SharedUtil.loadGpxFile(input)
		}
			?: throw IOException("Impossible d’ouvrir ce fichier")
		return load(gpxFile, displayName)
	}

	@Throws(IOException::class)
	fun load(gpxFile: GpxFile, sourceName: String? = null): FlightTrip {
		gpxFile.error?.let { throw IOException(it.message ?: "Fichier GPX invalide") }
		val displayName = sourceName?.takeIf { it.isNotBlank() }
			?: gpxFile.metadata.name?.takeIf { it.isNotBlank() }
			?: gpxFile.path.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }
			?: "Trace OsmAnd"

		val samples = mutableListOf<FlightSample>()
		val legs = mutableListOf<FlightLeg>()
		var totalDistance = 0.0
		val tracks = gpxFile.tracks.filterNot { it.generalTrack }
		for (track in tracks) {
			for (segment in track.segments.filterNot { it.generalSegment }) {
				if (segment.points.isEmpty()) continue
				val legIndex = legs.size
				val firstSampleIndex = samples.size
				var legDistance = 0.0
				var previousPoint: WptPt? = null
				for (point in segment.points) {
					val segmentDistance = previousPoint?.let { distanceMeters(it, point) } ?: 0.0
					legDistance += segmentDistance
					totalDistance += segmentDistance
					val calculatedSpeed = previousPoint?.let { previous ->
						val deltaMillis = point.time - previous.time
						if (deltaMillis > 0 && segmentDistance > 0) {
							(segmentDistance / (deltaMillis / 1_000.0)).toFloat()
						} else null
					}
					val recordedSpeed = point.speed.takeIf { it.isFinite() && it > 0f }
					val recordedBearing = point.bearing.takeIf { it.isFinite() }
						?: point.heading.takeIf { it.isFinite() }
					val hdop = point.hdop.takeIf { it.isFinite() && it > 0f }
					samples += FlightSample(
						index = samples.size,
						legIndex = legIndex,
						timestampMillis = point.time,
						latitude = point.lat,
						longitude = point.lon,
						altitudeMeters = point.ele.takeIf { it.isFinite() },
						speedMetersPerSecond = recordedSpeed ?: calculatedSpeed,
						bearingDegrees = recordedBearing,
						horizontalAccuracyMeters = null,
						hdop = hdop
					)
					previousPoint = point
				}
				val lastSampleIndex = samples.lastIndex
				val firstTime = samples[firstSampleIndex].timestampMillis.takeIf { it > 0L }
				val lastTime = samples[lastSampleIndex].timestampMillis.takeIf { it > 0L }
				legs += FlightLeg(
					index = legIndex,
					name = segment.name?.takeIf { it.isNotBlank() }
						?: track.name?.takeIf { it.isNotBlank() }
						?: "Étape ${legIndex + 1}",
					startSampleIndex = firstSampleIndex,
					endSampleIndex = lastSampleIndex,
					distanceMeters = legDistance,
					startTimeMillis = firstTime,
					endTimeMillis = lastTime
				)
			}
		}

		if (samples.isEmpty()) throw IOException("Ce GPX ne contient aucun point de trace")
		val resolvedSamples = FlightTrackMath.fillMissingBearings(samples)
		val timestamps = resolvedSamples.map { it.timestampMillis }
		val hasUsableTimestamps = timestamps.count { it > 0L } == timestamps.size &&
			timestamps.zipWithNext().all { (before, after) -> after >= before } &&
			timestamps.last() > timestamps.first()
		return FlightTrip(
			name = displayName.removeSuffix(".gpx").removeSuffix(".GPX"),
			samples = resolvedSamples,
			legs = legs,
			hasUsableTimestamps = hasUsableTimestamps,
			totalDistanceMeters = totalDistance,
			sourceDescription = displayName
		)
	}

	private fun findDisplayName(context: Context, uri: Uri): String? {
		return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
			val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
			if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
		}
	}

	private fun distanceMeters(a: WptPt, b: WptPt): Double {
		val result = FloatArray(1)
		Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, result)
		return result[0].toDouble()
	}
}
