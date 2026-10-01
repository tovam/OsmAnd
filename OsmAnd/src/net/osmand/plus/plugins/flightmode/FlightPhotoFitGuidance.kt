package net.osmand.plus.plugins.flightmode

import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.osmand.util.PhotoPoseSolver

/** Endpoints use canonical image coordinates; display rotation never changes the fit. */
internal data class FlightPhotoFitArrow(
    val pointIndex: Int,
    val x: Double,
    val y: Double,
    val targetX: Double,
    val targetY: Double,
) {
    fun screenVector(
        width: Double,
        height: Double,
        rotationDegrees: Double,
        length: Double,
    ): Pair<Double, Double>? {
        if (
            !width.isFinite() ||
                width <= 0 ||
                !height.isFinite() ||
                height <= 0 ||
                !rotationDegrees.isFinite() ||
                !length.isFinite() ||
                length <= 0
        )
            return null
        val dx = (targetX - x) * width
        val dy = (targetY - y) * height
        val norm = hypot(dx, dy)
        if (!norm.isFinite() || norm < 1e-9 || length <= 0) return null
        val angle = Math.toRadians(rotationDegrees)
        return length * (dx * cos(angle) - dy * sin(angle)) / norm to
            length * (dx * sin(angle) + dy * cos(angle)) / norm
    }
}

/** Ephemeral camera preview. It must never be saved as a validated calibration. */
internal data class FlightPhotoFitGuidance(
    val source: FlightPhotoCalibration,
    val reference: FlightPhotoSpatialPose,
    val camera: FlightPhotoFit,
) {
    fun compatible(
        data: FlightPhotoCalibration,
        currentReference: FlightPhotoSpatialPose?,
    ): Boolean =
        reference == currentReference &&
            source.imageWidth == data.imageWidth &&
            source.imageHeight == data.imageHeight &&
            source.fitFocal == data.fitFocal &&
            source.verticalFov == data.verticalFov

    private fun altitude(point: FlightPhotoControlPoint): Double? =
        point.altitude
            ?: source.points
                .firstOrNull {
                    it.latitude == point.latitude &&
                        it.longitude == point.longitude &&
                        it.altitude != null
                }
                ?.altitude

    fun arrows(data: FlightPhotoCalibration): List<FlightPhotoFitArrow> {
        if (
            !compatible(data, reference) ||
                data.imageWidth <= 0 ||
                data.imageHeight <= 0 ||
                camera.parameters.size != 7 ||
                camera.parameters.any { !it.isFinite() } ||
                !camera.originLatitude.isFinite() ||
                camera.originLatitude !in -90.0..90.0 ||
                !camera.originLongitude.isFinite() ||
                camera.originLongitude !in -180.0..180.0
        )
            return emptyList()
        val coordinates = FlightTerrainCoordinates(camera.originLatitude, camera.originLongitude)
        return data.points.mapIndexedNotNull { index, point ->
            val x = point.x ?: return@mapIndexedNotNull null
            val y = point.y ?: return@mapIndexedNotNull null
            val lat = point.latitude ?: return@mapIndexedNotNull null
            val lon = point.longitude ?: return@mapIndexedNotNull null
            val altitude = altitude(point) ?: return@mapIndexedNotNull null
            if (
                !x.isFinite() ||
                    !y.isFinite() ||
                    x !in 0.0..1.0 ||
                    y !in 0.0..1.0 ||
                    !lat.isFinite() ||
                    lat !in -90.0..90.0 ||
                    !lon.isFinite() ||
                    lon !in -180.0..180.0 ||
                    !altitude.isFinite()
            )
                return@mapIndexedNotNull null
            val world = coordinates.toLocal(lat, lon, altitude).map { it / 1000.0 }.toDoubleArray()
            val projected =
                PhotoPoseSolver.project(
                    camera.parameters.toDoubleArray(),
                    world,
                    data.imageWidth.toDouble() / data.imageHeight,
                ) ?: return@mapIndexedNotNull null
            val error =
                hypot((projected[0] - x) * data.imageWidth, (projected[1] - y) * data.imageHeight)
            if (!error.isFinite() || error < .25) null
            else FlightPhotoFitArrow(index, x, y, projected[0], projected[1])
        }
    }

    suspend fun refine(
        data: FlightPhotoCalibration,
        elevationAt: suspend (Double, Double) -> Double,
    ): FlightPhotoFitGuidance {
        require(compatible(data, reference) && reference.eyeAltitudeMeters?.isFinite() == true)
        val points =
            data.points.map { point ->
                if (
                    point.x != null &&
                        point.y != null &&
                        point.latitude != null &&
                        point.longitude != null
                ) {
                    require(
                        point.x.isFinite() &&
                            point.x in 0.0..1.0 &&
                            point.y.isFinite() &&
                            point.y in 0.0..1.0 &&
                            point.latitude.isFinite() &&
                            point.latitude in -90.0..90.0 &&
                            point.longitude.isFinite() &&
                            point.longitude in -180.0..180.0
                    )
                }
                if (
                    point.x != null &&
                        point.y != null &&
                        point.latitude != null &&
                        point.longitude != null
                )
                    point.copy(
                        altitude = altitude(point) ?: elevationAt(point.latitude, point.longitude)
                    )
                else point
            }
        val indices =
            points.indices.filter { i ->
                points[i].let {
                    it.x != null &&
                        it.y != null &&
                        it.latitude != null &&
                        it.longitude != null &&
                        it.altitude != null
                }
            }
        val coordinates = FlightTerrainCoordinates(camera.originLatitude, camera.originLongitude)
        val world =
            indices
                .map { i ->
                    points[i].let {
                        coordinates
                            .toLocal(it.latitude!!, it.longitude!!, it.altitude!!)
                            .map { v -> v / 1000.0 }
                            .toDoubleArray()
                    }
                }
                .toTypedArray()
        val image = indices.map { i -> doubleArrayOf(points[i].x!!, points[i].y!!) }.toTypedArray()
        val eye =
            coordinates.toLocal(
                reference.eyeLatitude,
                reference.eyeLongitude,
                reference.eyeAltitudeMeters!!.toDouble(),
            )
        val initial =
            doubleArrayOf(
                eye[0] / 1000.0,
                eye[1] / 1000.0,
                eye[2] / 1000.0,
                Math.toRadians(reference.viewAzimuthDegrees.toDouble()),
                Math.toRadians(reference.viewElevationDegrees.toDouble()),
                0.0,
                ln(.5 / tan(Math.toRadians(data.verticalFov / 2))),
            )
        val parameters =
            runInterruptible(Dispatchers.Default) {
                PhotoPoseSolver.refine(
                    world,
                    image,
                    initial,
                    data.imageWidth,
                    data.imageHeight,
                    data.fitFocal,
                    camera.parameters.toDoubleArray(),
                )
            }
        return copy(
            source = data.copy(points = points, fit = null),
            camera =
                camera.copy(
                    parameters = parameters.toList(),
                    weak = camera.weak || indices.size == 4,
                    errors = emptyList(),
                    influences = null,
                    pointIndices = indices,
                ),
        )
    }
}
