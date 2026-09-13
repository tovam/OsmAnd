package net.osmand.plus.plugins.flightmode

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class FlightPhotoInspection(
    val camera: FlightPhotoSpatialPose,
    val original: FlightPhotoSpatialPose,
    val estimated: FlightPhotoSpatialPose?,
    val trip: FlightTrip?,
)

/** Actual small sphere meshes and the recorded 3D centreline in the terrain's frame. */
internal class FlightInspectionRenderer {
    private val program: Int
    private val position: Int
    private val color: Int
    private val matrix: Int
    private var track: FlightTrip? = null
    private var originLatitude = Double.NaN
    private var originLongitude = Double.NaN
    private var trackBuffer: java.nio.FloatBuffer? = null

    init {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source)
            GLES20.glCompileShader(id)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }
            return id
        }
        val v =
            shader(
                GLES20.GL_VERTEX_SHADER,
                "uniform mat4 uMatrix; attribute vec3 aPosition; void main(){gl_Position=uMatrix*vec4(aPosition,1.0);}",
            )
        val f =
            shader(
                GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}",
            )
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        position = GLES20.glGetAttribLocation(program, "aPosition")
        color = GLES20.glGetUniformLocation(program, "uColor")
        matrix = GLES20.glGetUniformLocation(program, "uMatrix")
    }

    fun draw(
        state: FlightPhotoInspection,
        coordinates: FlightTerrainCoordinates,
        eye: FloatArray,
        mvp: FloatArray,
    ) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(matrix, 1, false, mvp, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        if (
            track !== state.trip ||
                originLatitude != coordinates.centerLatitude ||
                originLongitude != coordinates.centerLongitude
        ) {
            track = state.trip
            originLatitude = coordinates.centerLatitude
            originLongitude = coordinates.centerLongitude
            val samples = state.trip?.samples.orEmpty()
            val route = ArrayList<Float>()
            val stride = max(1, samples.size / 1000)
            samples.forEachIndexed { i, s ->
                if ((i % stride == 0 || i == samples.lastIndex) && s.altitudeMeters != null) {
                    coordinates.toLocal(s.latitude, s.longitude, s.altitudeMeters).forEach {
                        route.add(it)
                    }
                }
            }
            trackBuffer = buffer(route.toFloatArray())
        }
        trackBuffer?.let { drawBuffer(it, GLES20.GL_LINE_STRIP, floatArrayOf(1f, 0.55f, 0.2f, 1f)) }
        listOfNotNull(
                state.original to floatArrayOf(1f, 0.55f, 0.2f, 1f),
                state.estimated?.let { it to floatArrayOf(0.75f, 0.4f, 1f, 1f) },
            )
            .forEach { (pose, rgb) ->
                val center =
                    coordinates.toLocal(
                        pose.eyeLatitude,
                        pose.eyeLongitude,
                        (pose.eyeAltitudeMeters ?: 0f).toDouble(),
                    )
                val distance =
                    sqrt(
                            center.indices.sumOf {
                                ((center[it] - eye[it]) * (center[it] - eye[it])).toDouble()
                            }
                        )
                        .toFloat()
                if (distance > 1f) {
                    val radius = (distance * 0.004f).coerceIn(0.3f, 100f)
                    val mesh = ArrayList<Float>()
                    fun vertex(a: Int, b: Int) {
                        val lat = -PI / 2 + a * PI / 8
                        val lon = b * 2 * PI / 12
                        mesh.add(center[0] + (cos(lat) * cos(lon) * radius).toFloat())
                        mesh.add(center[1] + (sin(lat) * radius).toFloat())
                        mesh.add(center[2] + (cos(lat) * sin(lon) * radius).toFloat())
                    }
                    for (a in 0 until 8) for (b in 0 until 12) {
                        vertex(a, b)
                        vertex(a + 1, b)
                        vertex(a, b + 1)
                        vertex(a, b + 1)
                        vertex(a + 1, b)
                        vertex(a + 1, b + 1)
                    }
                    draw(mesh.toFloatArray(), GLES20.GL_TRIANGLES, rgb)
                }
            }
        GLES20.glDisableVertexAttribArray(position)
    }

    private fun draw(vertices: FloatArray, mode: Int, rgb: FloatArray) {
        if (vertices.isEmpty()) return
        drawBuffer(buffer(vertices), mode, rgb)
    }

    private fun buffer(vertices: FloatArray) =
        ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

    private fun drawBuffer(buffer: java.nio.FloatBuffer, mode: Int, rgb: FloatArray) {
        GLES20.glUniform4fv(color, 1, rgb, 0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glDrawArrays(mode, 0, buffer.limit() / 3)
    }
}
