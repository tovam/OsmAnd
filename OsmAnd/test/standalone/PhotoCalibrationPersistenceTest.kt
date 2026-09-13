import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import net.osmand.plus.plugins.flightmode.*
import net.osmand.util.PhotoPlaneGeometry
import net.osmand.util.PhotoPoseSolver
import org.json.JSONObject

fun main() {
    for ((lat, lon) in listOf(48.0 to 2.0, -33.0 to 151.0, 70.0 to 179.9)) {
        val coordinates = FlightTerrainCoordinates(lat, lon)
        for (delta in listOf(0.0, 0.05, 0.8)) {
            val local = coordinates.toLocal(lat + delta, lon - delta, 12000.0)
            val geo = coordinates.toGeographic(local.map { it.toDouble() }.toDoubleArray())
            check(abs(geo[0] - (lat + delta)) < 1e-6)
            check(abs(geo[1] - (lon - delta)) < 1e-6)
            check(abs(geo[2] - 12000) < 0.02)
            val direction = floatArrayOf(0.3f, 0.4f, -0.8f)
            val roundtrip =
                coordinates.vectorFromLocal(
                    lat + delta,
                    lon - delta,
                    coordinates.vectorToLocal(
                        lat + delta,
                        lon - delta,
                        direction[0],
                        direction[1],
                        direction[2],
                    ),
                )
            direction.indices.forEach { check(abs(direction[it] - roundtrip[it]) < 1e-6) }
        }
    }
    val data =
        FlightPhotoCalibration(
            points =
                List(5) {
                    FlightPhotoControlPoint(0.1 * it, 0.2 * it, 45.0 + it / 100.0, 2.0, 123.5)
                },
            imageWidth = 1500,
            imageHeight = 1000,
            fitFocal = false,
            verticalFov = 52.0,
            fit =
                FlightPhotoFit(
                    45.0,
                    2.0,
                    listOf(1.0, 12.0, 2.0, 0.3, -0.2, 0.1, 0.5),
                    List(5) { 0.3 },
                    0.3,
                    false,
                ),
            editorView = FlightPhotoEditorView(1, 45f, -30f, 2f, 0.3f),
        )
    check(FlightPhotoCalibration.fromJson(JSONObject(data.toJson().toString())) == data)
    check(FlightPhotoCalibration.fromJson(null) == FlightPhotoCalibration())
    check(
        FlightPhotoCalibration.fromJson(JSONObject("{\"version\":100}")) == FlightPhotoCalibration()
    )
    val partial =
        FlightPhotoCalibration(
            points = listOf(FlightPhotoControlPoint(0.2, 0.4), FlightPhotoControlPoint())
        )
    check(FlightPhotoCalibration.fromJson(JSONObject(partial.toJson().toString())) == partial)
    val brokenFit =
        data.toJson().apply { getJSONObject("fit").put("parameters", org.json.JSONArray()) }
    check(FlightPhotoCalibration.fromJson(brokenFit).points == data.points)
    check(FlightPhotoCalibration.fromJson(brokenFit).fit == null)
    val reference = FlightPhotoSpatialPose(12.5, null, 45.0, 2.0, 12000f, 0f, 0f, -20f, 60f)
    // The actual GL photo rectangle must invert the solver's projection, including
    // camera roll and the change of tangent basis between recorded and fitted eyes.
    for (roll in listOf(-2.9, -0.7, 0.0, 0.9, 2.8)) {
        val f = data.fit!!.copy(parameters = listOf(12.0, 12.0, -8.0, 0.6, -0.3, roll, 0.5))
        val pose = f.pose(reference, 1.5f)
        val c = FlightTerrainCoordinates(f.originLatitude, f.originLongitude)
        val yaw = Math.toRadians(pose.viewAzimuthDegrees.toDouble())
        val pitch = Math.toRadians(pose.viewElevationDegrees.toDouble())
        fun vector(e: Double, u: Double, s: Double) =
            c.vectorToLocal(
                pose.eyeLatitude,
                pose.eyeLongitude,
                e.toFloat(),
                u.toFloat(),
                s.toFloat(),
            )
        val forward = vector(sin(yaw) * cos(pitch), sin(pitch), -cos(yaw) * cos(pitch))
        val right = vector(cos(yaw), 0.0, sin(yaw))
        val up = vector(-sin(yaw) * sin(pitch), cos(pitch), cos(yaw) * sin(pitch))
        val corners =
            PhotoPlaneGeometry.vertices(
                c.toLocal(pose.eyeLatitude, pose.eyeLongitude, pose.eyeAltitudeMeters!!.toDouble()),
                forward,
                right,
                up,
                1000f,
                pose.verticalFieldOfViewDegrees,
                1.5f,
                1.5f,
                1f,
                0f,
                0f,
                f.imageRotationDegrees(),
            )
        for (i in 0..3) {
            val uv =
                PhotoPoseSolver.project(
                    f.parameters.toDoubleArray(),
                    DoubleArray(3) { corners[i * 3 + it] / 1000.0 },
                    1.5,
                )
            check(abs(uv[0] - if (i < 2) 0.0 else 1.0) < 0.0001)
            check(abs(uv[1] - if (i % 2 == 0) 0.0 else 1.0) < 0.0001)
        }
    }
    println("WGS84, JSON persistence/recovery and solver-to-GL photo projection passed")
}
