package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class FlightTerrainView @JvmOverloads constructor(
	context: Context,
	attributes: AttributeSet? = null
) : GLSurfaceView(context, attributes) {

	private val terrainRenderer = TerrainRenderer { message ->
		post { rendererErrorListener?.invoke(message) }
	}
	private var rendererErrorListener: ((String) -> Unit)? = null

	init {
		setEGLContextClientVersion(2)
		setZOrderMediaOverlay(true)
		setPreserveEGLContextOnPause(true)
		setRenderer(terrainRenderer)
		renderMode = RENDERMODE_WHEN_DIRTY
	}

	fun updateScene(
		scene: FlightTerrainScene?,
		sample: FlightSample?,
		pose: FlightHeadPose,
		shadingEnabled: Boolean,
		onRendererError: (String) -> Unit
	) {
		rendererErrorListener = onRendererError
		terrainRenderer.update(scene, sample, pose, shadingEnabled)
		requestRender()
	}

	private class TerrainRenderer(
		private val onError: (String) -> Unit
	) : GLSurfaceView.Renderer {

		@Volatile
		private var scene: FlightTerrainScene? = null
		@Volatile
		private var sample: FlightSample? = null
		@Volatile
		private var pose: FlightHeadPose = FlightHeadPose()
		@Volatile
		private var shadingEnabled: Boolean = true

		private var program = 0
		private var surfaceWidth = 1
		private var surfaceHeight = 1
		private var uploadedGeneration = Long.MIN_VALUE
		private var renderMeshes: List<RenderMesh> = emptyList()

		private var positionLocation = -1
		private var normalLocation = -1
		private var elevationLocation = -1
		private var mvpLocation = -1
		private var cameraLocation = -1
		private var lightLocation = -1
		private var fogDistanceLocation = -1
		private var shadingLocation = -1
		private var skyColorLocation = -1
		private var daylightLocation = -1

		fun update(
			scene: FlightTerrainScene?,
			sample: FlightSample?,
			pose: FlightHeadPose,
			shadingEnabled: Boolean
		) {
			this.scene = scene
			this.sample = sample
			this.pose = pose.clamped()
			this.shadingEnabled = shadingEnabled
		}

		override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
			try {
				program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
				positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
				normalLocation = GLES20.glGetAttribLocation(program, "aNormal")
				elevationLocation = GLES20.glGetAttribLocation(program, "aElevation")
				mvpLocation = GLES20.glGetUniformLocation(program, "uMvp")
				cameraLocation = GLES20.glGetUniformLocation(program, "uCameraPosition")
				lightLocation = GLES20.glGetUniformLocation(program, "uLightDirection")
				fogDistanceLocation = GLES20.glGetUniformLocation(program, "uFogDistance")
				shadingLocation = GLES20.glGetUniformLocation(program, "uShadingEnabled")
				skyColorLocation = GLES20.glGetUniformLocation(program, "uSkyColor")
				daylightLocation = GLES20.glGetUniformLocation(program, "uDaylight")
				GLES20.glEnable(GLES20.GL_DEPTH_TEST)
				GLES20.glDepthFunc(GLES20.GL_LEQUAL)
				GLES20.glDisable(GLES20.GL_CULL_FACE)
				GLES20.glClearColor(SKY_RED, SKY_GREEN, SKY_BLUE, 1f)
			} catch (error: RuntimeException) {
				program = 0
				onError(error.message ?: "Initialisation OpenGL impossible")
			}
		}

		override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
			surfaceWidth = width.coerceAtLeast(1)
			surfaceHeight = height.coerceAtLeast(1)
			GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
		}

		override fun onDrawFrame(gl: GL10?) {
			if (program == 0) {
				GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
				return
			}
			val currentScene = scene
			val currentSample = sample
			val latitude = currentSample?.latitude ?: currentScene?.centerLatitude ?: 0.0
			val longitude = currentSample?.longitude ?: currentScene?.centerLongitude ?: 0.0
			val sun = FlightSunPosition.direction(
				currentSample?.timestampMillis ?: System.currentTimeMillis(),
				latitude,
				longitude
			)
			val daylight = ((sun.up + 0.08f) / 0.22f).coerceIn(0f, 1f)
			val sky = skyColor(daylight)
			GLES20.glClearColor(sky[0], sky[1], sky[2], 1f)
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
			if (currentScene == null) return
			if (uploadedGeneration != currentScene.generation) {
				renderMeshes = currentScene.meshes.map(::createRenderMesh)
				uploadedGeneration = currentScene.generation
			}
			if (renderMeshes.isEmpty()) return

			val ground = currentScene.centerGroundElevationMeters ?: 0f
			val reportedAltitude = currentSample?.altitudeMeters?.toFloat() ?: DEFAULT_FLIGHT_ALTITUDE_METERS
			val altitude = max(reportedAltitude, ground + MINIMUM_GROUND_CLEARANCE_METERS)
			val coordinates = FlightTerrainCoordinates(currentScene.centerLatitude, currentScene.centerLongitude)
			val camera = coordinates.toLocal(latitude, longitude, altitude.toDouble())
			val bearing = currentSample?.bearingDegrees ?: DEFAULT_BEARING_DEGREES
			val viewAzimuth = Math.toRadians((bearing + RIGHT_WINDOW_OFFSET_DEGREES).toDouble())
			val directionX = sin(viewAzimuth).toFloat()
			val directionZ = (-cos(viewAzimuth)).toFloat()

			val view = FloatArray(16)
			Matrix.setLookAtM(
				view,
				0,
				camera[0], camera[1], camera[2],
				camera[0] + directionX, camera[1], camera[2] + directionZ,
				0f, 1f, 0f
			)
			val projection = offAxisProjection(pose, currentScene.radiusKm)
			val mvp = FloatArray(16)
			Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

			GLES20.glUseProgram(program)
			GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
			GLES20.glUniform3f(cameraLocation, camera[0], camera[1], camera[2])
			GLES20.glUniform3f(lightLocation, sun.east, sun.up, -sun.north)
			GLES20.glUniform1f(fogDistanceLocation, currentScene.radiusKm * 1_000f * 0.92f)
			GLES20.glUniform1f(shadingLocation, if (shadingEnabled) 1f else 0f)
			GLES20.glUniform3f(skyColorLocation, sky[0], sky[1], sky[2])
			GLES20.glUniform1f(daylightLocation, daylight)
			for (mesh in renderMeshes) drawMesh(mesh)
			GLES20.glDisableVertexAttribArray(positionLocation)
			GLES20.glDisableVertexAttribArray(normalLocation)
			GLES20.glDisableVertexAttribArray(elevationLocation)
		}

		private fun offAxisProjection(pose: FlightHeadPose, radiusKm: Int): FloatArray {
			val projection = FloatArray(16)
			val aspect = surfaceWidth.toFloat() / surfaceHeight
			val halfWindowHeight = WINDOW_HALF_SIZE_METERS
			val halfWindowWidth = halfWindowHeight * aspect
			val distance = pose.distanceMeters.coerceAtLeast(FlightHeadPose.MIN_DISTANCE_METERS)
			val scale = NEAR_PLANE_METERS / distance
			val left = (-halfWindowWidth - pose.horizontalMeters) * scale
			val right = (halfWindowWidth - pose.horizontalMeters) * scale
			val bottom = (-halfWindowHeight + pose.verticalMeters) * scale
			val top = (halfWindowHeight + pose.verticalMeters) * scale
			val far = max(MINIMUM_FAR_PLANE_METERS, radiusKm * 1_000f * 2.2f)
			Matrix.frustumM(projection, 0, left, right, bottom, top, NEAR_PLANE_METERS, far)
			return projection
		}

		private fun drawMesh(mesh: RenderMesh) {
			val strideBytes = FlightTerrainMeshBuilder.VERTEX_COMPONENTS * FLOAT_BYTES
			mesh.vertices.position(0)
			GLES20.glEnableVertexAttribArray(positionLocation)
			GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, strideBytes, mesh.vertices)
			mesh.vertices.position(3)
			GLES20.glEnableVertexAttribArray(normalLocation)
			GLES20.glVertexAttribPointer(normalLocation, 3, GLES20.GL_FLOAT, false, strideBytes, mesh.vertices)
			mesh.vertices.position(6)
			GLES20.glEnableVertexAttribArray(elevationLocation)
			GLES20.glVertexAttribPointer(elevationLocation, 1, GLES20.GL_FLOAT, false, strideBytes, mesh.vertices)
			mesh.indices.position(0)
			GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
		}

		private fun skyColor(daylight: Float): FloatArray {
			val twilight = ((daylight - 0.05f) / 0.45f).coerceIn(0f, 1f)
			val day = ((daylight - 0.35f) / 0.65f).coerceIn(0f, 1f)
			val red = mix(NIGHT_SKY_RED, TWILIGHT_SKY_RED, twilight)
			val green = mix(NIGHT_SKY_GREEN, TWILIGHT_SKY_GREEN, twilight)
			val blue = mix(NIGHT_SKY_BLUE, TWILIGHT_SKY_BLUE, twilight)
			return floatArrayOf(
				mix(red, SKY_RED, day),
				mix(green, SKY_GREEN, day),
				mix(blue, SKY_BLUE, day)
			)
		}

		private fun mix(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount

		private fun createRenderMesh(mesh: FlightTerrainMesh): RenderMesh {
			val vertexBuffer = ByteBuffer.allocateDirect(mesh.vertices.size * FLOAT_BYTES)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer()
				.apply {
					put(mesh.vertices)
					position(0)
				}
			val indexBuffer = ByteBuffer.allocateDirect(mesh.indices.size * SHORT_BYTES)
				.order(ByteOrder.nativeOrder())
				.asShortBuffer()
				.apply {
					put(mesh.indices)
					position(0)
				}
			return RenderMesh(vertexBuffer, indexBuffer, mesh.indices.size)
		}

		private fun createProgram(vertexSource: String, fragmentSource: String): Int {
			val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
			val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
			val result = GLES20.glCreateProgram()
			GLES20.glAttachShader(result, vertexShader)
			GLES20.glAttachShader(result, fragmentShader)
			GLES20.glLinkProgram(result)
			val status = IntArray(1)
			GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
			GLES20.glDeleteShader(vertexShader)
			GLES20.glDeleteShader(fragmentShader)
			if (status[0] == 0) {
				val message = GLES20.glGetProgramInfoLog(result)
				GLES20.glDeleteProgram(result)
				throw IllegalStateException("Shader terrain non lié: $message")
			}
			return result
		}

		private fun compileShader(type: Int, source: String): Int {
			val shader = GLES20.glCreateShader(type)
			GLES20.glShaderSource(shader, source)
			GLES20.glCompileShader(shader)
			val status = IntArray(1)
			GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
			if (status[0] == 0) {
				val message = GLES20.glGetShaderInfoLog(shader)
				GLES20.glDeleteShader(shader)
				throw IllegalStateException("Shader terrain invalide: $message")
			}
			return shader
		}

		private data class RenderMesh(
			val vertices: FloatBuffer,
			val indices: ShortBuffer,
			val indexCount: Int
		)

		companion object {
			private const val FLOAT_BYTES = 4
			private const val SHORT_BYTES = 2
			private const val DEFAULT_FLIGHT_ALTITUDE_METERS = 10_000f
			private const val MINIMUM_GROUND_CLEARANCE_METERS = 60f
			private const val DEFAULT_BEARING_DEGREES = 0f
			private const val RIGHT_WINDOW_OFFSET_DEGREES = 90f
			private const val WINDOW_HALF_SIZE_METERS = 0.125f
			private const val NEAR_PLANE_METERS = 10f
			private const val MINIMUM_FAR_PLANE_METERS = 750_000f
			private const val SKY_RED = 0.22f
			private const val SKY_GREEN = 0.48f
			private const val SKY_BLUE = 0.69f
			private const val TWILIGHT_SKY_RED = 0.47f
			private const val TWILIGHT_SKY_GREEN = 0.25f
			private const val TWILIGHT_SKY_BLUE = 0.30f
			private const val NIGHT_SKY_RED = 0.015f
			private const val NIGHT_SKY_GREEN = 0.025f
			private const val NIGHT_SKY_BLUE = 0.065f
			private const val VERTEX_SHADER = """
				uniform mat4 uMvp;
				uniform vec3 uCameraPosition;
				uniform vec3 uLightDirection;
				uniform float uFogDistance;
				uniform float uShadingEnabled;
				attribute vec3 aPosition;
				attribute vec3 aNormal;
				attribute float aElevation;
				varying float vElevation;
				varying float vLight;
				varying float vFog;
				varying float vSlope;
				void main() {
					vec3 normal = normalize(aNormal);
					float directional = 0.28 + 0.72 * max(dot(normal, normalize(uLightDirection)), 0.0);
					vLight = mix(1.0, directional, uShadingEnabled);
					vElevation = aElevation;
					vSlope = 1.0 - clamp(normal.y, 0.0, 1.0);
					vFog = clamp(distance(aPosition, uCameraPosition) / uFogDistance, 0.0, 1.0);
					gl_Position = uMvp * vec4(aPosition, 1.0);
				}
			"""

			private const val FRAGMENT_SHADER = """
				precision mediump float;
				uniform vec3 uSkyColor;
				uniform float uDaylight;
				varying float vElevation;
				varying float vLight;
				varying float vFog;
				varying float vSlope;
				void main() {
					vec3 water = vec3(0.09, 0.31, 0.46);
					vec3 lowland = vec3(0.22, 0.40, 0.20);
					vec3 upland = vec3(0.43, 0.39, 0.25);
					vec3 rock = vec3(0.46, 0.45, 0.42);
					vec3 snow = vec3(0.88, 0.90, 0.91);
					float highlandAmount = smoothstep(250.0, 1800.0, vElevation);
					vec3 terrain = mix(lowland, upland, highlandAmount);
					terrain = mix(terrain, rock, smoothstep(0.18, 0.62, vSlope));
					terrain = mix(terrain, snow, smoothstep(2400.0, 3400.0, vElevation));
					vec3 base = mix(water, terrain, step(0.0, vElevation));
					vec3 lit = base * vLight * mix(0.08, 1.0, uDaylight);
					float fogAmount = smoothstep(0.68, 1.0, vFog);
					gl_FragColor = vec4(mix(lit, uSkyColor, fogAmount), 1.0);
				}
			"""
		}
	}
}
