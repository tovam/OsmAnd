// Compile this fixture only for the standalone JVM test, never with Android sources.
// No filesystem/network access: elevation is deliberately unavailable in this fixture.
package net.osmand.plus.plugins.flightmode
data class FlightPhotoSpatialPose(
    val samplePosition: Double,
    val timestampMillis: Long?,
    val eyeLatitude: Double,
    val eyeLongitude: Double,
    val eyeAltitudeMeters: Float?,
    val aircraftBearingDegrees: Float,
    val viewAzimuthDegrees: Float,
    val viewElevationDegrees: Float,
    val verticalFieldOfViewDegrees: Float,
    val referenceAspectRatio: Float? = null,
)

class FlightTerrainRepository {
    suspend fun calibrationElevation(lat: Double, lon: Double): Double =
        error("No network in synthetic test")
}
