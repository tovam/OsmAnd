package net.osmand.plus.plugins.flightmode

import java.security.MessageDigest
import kotlin.math.roundToLong

/**
 * Stable identity of a recorded trace.
 *
 * Names, derived bearings and optional sensor values deliberately do not participate: the same
 * GPX can acquire another display name or have its missing bearings reconstructed when it is
 * reopened. Coordinates and original timestamps keep two actual recordings distinct.
 */
object FlightTripFingerprint {

	fun create(trip: FlightTrip): String {
		val digest = MessageDigest.getInstance("SHA-256")
		digest.update(FINGERPRINT_VERSION.toByteArray(Charsets.UTF_8))
		digest.updateLong(trip.samples.size.toLong())
		trip.samples.forEach { sample ->
			digest.updateLong((sample.latitude * COORDINATE_SCALE).roundToLong())
			digest.updateLong((sample.longitude * COORDINATE_SCALE).roundToLong())
			digest.updateLong(sample.timestampMillis.coerceAtLeast(0L))
		}
		return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
	}

	private fun MessageDigest.updateLong(value: Long) {
		for (shift in 56 downTo 0 step 8) update(((value ushr shift) and 0xff).toByte())
	}

	private const val FINGERPRINT_VERSION = "osmand-flight-track-v1"
	private const val COORDINATE_SCALE = 10_000_000.0
}
