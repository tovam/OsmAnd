package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
		windowPlacement: FlightWindowPlacement,
		altitudeOverrideMeters: Float?,
		shadingEnabled: Boolean,
		onRendererError: (String) -> Unit
	) {
		rendererErrorListener = onRendererError
		terrainRenderer.update(scene, sample, windowPlacement, altitudeOverrideMeters, shadingEnabled)
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
		private var windowPlacement: FlightWindowPlacement = FlightWindowPlacement()
		@Volatile
		private var altitudeOverrideMeters: Float? = null
		@Volatile
		private var shadingEnabled: Boolean = true

		private var program = 0
		private var shadowProgram = 0
		private var surfaceWidth = 1
		private var surfaceHeight = 1
		private var uploadedGeneration = Long.MIN_VALUE
		private var renderMeshes: List<RenderMesh> = emptyList()

		private var positionLocation = -1
		private var normalLocation = -1
		private var elevationLocation = -1
		private var textureCoordinateLocation = -1
		private var mvpLocation = -1
		private var lightMvpLocation = -1
		private var cameraLocation = -1
		private var lightLocation = -1
		private var fogDistanceLocation = -1
		private var shadingLocation = -1
		private var skyColorLocation = -1
		private var daylightLocation = -1
		private var satelliteTextureLocation = -1
		private var hasSatelliteTextureLocation = -1
		private var shadowTextureLocation = -1
		private var shadowTexelSizeLocation = -1
		private var shadowsEnabledLocation = -1

		private var shadowPositionLocation = -1
		private var shadowMvpLocation = -1
		private var shadowFrameBuffer = 0
		private var shadowTexture = 0
		private var shadowDepthBuffer = 0
		private var shadowMapSize = 0
		private var shadowAvailable = false
		private var shadowSceneGeneration = Long.MIN_VALUE
		private var shadowSunEast = Float.NaN
		private var shadowSunNorth = Float.NaN
		private var shadowSunUp = Float.NaN
		private var shadowLightMvp = FloatArray(16)

		fun update(
			scene: FlightTerrainScene?,
			sample: FlightSample?,
			windowPlacement: FlightWindowPlacement,
			altitudeOverrideMeters: Float?,
			shadingEnabled: Boolean
		) {
			this.scene = scene
			this.sample = sample
			this.windowPlacement = windowPlacement.clamped()
			this.altitudeOverrideMeters = altitudeOverrideMeters
			this.shadingEnabled = shadingEnabled
		}

		override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
			try {
				program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
				shadowProgram = createProgram(SHADOW_VERTEX_SHADER, SHADOW_FRAGMENT_SHADER)
				positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
				normalLocation = GLES20.glGetAttribLocation(program, "aNormal")
				elevationLocation = GLES20.glGetAttribLocation(program, "aElevation")
				textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
				mvpLocation = GLES20.glGetUniformLocation(program, "uMvp")
				lightMvpLocation = GLES20.glGetUniformLocation(program, "uLightMvp")
				cameraLocation = GLES20.glGetUniformLocation(program, "uCameraPosition")
				lightLocation = GLES20.glGetUniformLocation(program, "uLightDirection")
				fogDistanceLocation = GLES20.glGetUniformLocation(program, "uFogDistance")
				shadingLocation = GLES20.glGetUniformLocation(program, "uShadingEnabled")
				skyColorLocation = GLES20.glGetUniformLocation(program, "uSkyColor")
				daylightLocation = GLES20.glGetUniformLocation(program, "uDaylight")
				satelliteTextureLocation = GLES20.glGetUniformLocation(program, "uSatelliteTexture")
				hasSatelliteTextureLocation = GLES20.glGetUniformLocation(program, "uHasSatelliteTexture")
				shadowTextureLocation = GLES20.glGetUniformLocation(program, "uShadowMap")
				shadowTexelSizeLocation = GLES20.glGetUniformLocation(program, "uShadowTexelSize")
				shadowsEnabledLocation = GLES20.glGetUniformLocation(program, "uShadowsEnabled")
				shadowPositionLocation = GLES20.glGetAttribLocation(shadowProgram, "aPosition")
				shadowMvpLocation = GLES20.glGetUniformLocation(shadowProgram, "uLightMvp")
				shadowAvailable = createShadowResources()
				uploadedGeneration = Long.MIN_VALUE
				renderMeshes = emptyList()
				shadowSceneGeneration = Long.MIN_VALUE
				GLES20.glEnable(GLES20.GL_DEPTH_TEST)
				GLES20.glDepthFunc(GLES20.GL_LEQUAL)
				GLES20.glDisable(GLES20.GL_CULL_FACE)
				GLES20.glDisable(GLES20.GL_DITHER)
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
			val currentWindowPlacement = windowPlacement
			val latitude = currentSample?.latitude ?: currentScene?.centerLatitude ?: 0.0
			val longitude = currentSample?.longitude ?: currentScene?.centerLongitude ?: 0.0
			val sun = FlightSunPosition.direction(
				currentSample?.timestampMillis ?: System.currentTimeMillis(),
				latitude,
				longitude
			)
			val daylight = ((sun.up + 0.08f) / 0.22f).coerceIn(0f, 1f)
			val sky = skyColor(daylight)
			if (currentScene == null) {
				clearDefaultFrameBuffer(sky)
				return
			}
			if (uploadedGeneration != currentScene.generation) {
				releaseRenderMeshes()
				renderMeshes = currentScene.meshes.map(::createRenderMesh)
				uploadedGeneration = currentScene.generation
			}
			if (renderMeshes.isEmpty()) {
				clearDefaultFrameBuffer(sky)
				return
			}

			val lightMvp = createLightMvp(currentScene, sun)
			val shadowsActive = shadingEnabled && shadowAvailable && sun.up > MINIMUM_SHADOW_SUN_UP
			if (shadowsActive && shouldUpdateShadowMap(currentScene, sun)) {
				renderShadowMap(lightMvp)
				lightMvp.copyInto(shadowLightMvp)
				shadowSceneGeneration = currentScene.generation
				shadowSunEast = sun.east
				shadowSunNorth = sun.north
				shadowSunUp = sun.up
			}
			clearDefaultFrameBuffer(sky)

			val ground = currentScene.centerGroundElevationMeters ?: 0f
			val reportedAltitude = altitudeOverrideMeters
				?: currentSample?.altitudeMeters?.toFloat()
				?: DEFAULT_FLIGHT_ALTITUDE_METERS
			val altitude = max(reportedAltitude, ground + MINIMUM_GROUND_CLEARANCE_METERS)
			val coordinates = FlightTerrainCoordinates(currentScene.centerLatitude, currentScene.centerLongitude)
			val camera = coordinates.toLocal(latitude, longitude, altitude.toDouble())
			val bearing = currentSample?.bearingDegrees ?: DEFAULT_BEARING_DEGREES
			val geometry = currentWindowPlacement.geometry()
			val viewAzimuth = Math.toRadians(bearing.toDouble()) + geometry.relativeAzimuthRadians
			val horizontalDirection = cos(geometry.elevationRadians)
			val directionX = (sin(viewAzimuth) * horizontalDirection).toFloat()
			val directionY = sin(geometry.elevationRadians)
			val directionZ = (-cos(viewAzimuth) * horizontalDirection).toFloat()

			val view = FloatArray(16)
			Matrix.setLookAtM(
				view,
				0,
				camera[0], camera[1], camera[2],
				camera[0] + directionX, camera[1] + directionY, camera[2] + directionZ,
				0f, 1f, 0f
			)
			val projection = windowProjection(currentWindowPlacement, currentScene.radiusKm)
			val mvp = FloatArray(16)
			Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

			GLES20.glUseProgram(program)
			GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
			GLES20.glUniformMatrix4fv(
				lightMvpLocation,
				1,
				false,
				if (shadowsActive) shadowLightMvp else lightMvp,
				0
			)
			GLES20.glUniform3f(cameraLocation, camera[0], camera[1], camera[2])
			GLES20.glUniform3f(lightLocation, sun.east, sun.up, -sun.north)
			GLES20.glUniform1f(fogDistanceLocation, currentScene.radiusKm * 1_000f * 0.92f)
			GLES20.glUniform1f(shadingLocation, if (shadingEnabled) 1f else 0f)
			GLES20.glUniform3f(skyColorLocation, sky[0], sky[1], sky[2])
			GLES20.glUniform1f(daylightLocation, daylight)
			GLES20.glUniform1f(shadowsEnabledLocation, if (shadowsActive) 1f else 0f)
			GLES20.glUniform2f(
				shadowTexelSizeLocation,
				if (shadowMapSize > 0) 1f / shadowMapSize else 0f,
				if (shadowMapSize > 0) 1f / shadowMapSize else 0f
			)
			GLES20.glUniform1i(satelliteTextureLocation, SATELLITE_TEXTURE_UNIT)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + SHADOW_TEXTURE_UNIT)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (shadowsActive) shadowTexture else 0)
			GLES20.glUniform1i(shadowTextureLocation, SHADOW_TEXTURE_UNIT)
			for (mesh in renderMeshes) drawMesh(mesh)
			GLES20.glDisableVertexAttribArray(positionLocation)
			GLES20.glDisableVertexAttribArray(normalLocation)
			GLES20.glDisableVertexAttribArray(elevationLocation)
			GLES20.glDisableVertexAttribArray(textureCoordinateLocation)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + SHADOW_TEXTURE_UNIT)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
		}

		private fun windowProjection(placement: FlightWindowPlacement, radiusKm: Int): FloatArray {
			val projection = FloatArray(16)
			val aspect = surfaceWidth.toFloat() / surfaceHeight
			val far = max(MINIMUM_FAR_PLANE_METERS, radiusKm * 1_000f * 2.2f)
			val fieldOfView = (FlightWindowPlacement.DEFAULT_VERTICAL_FIELD_OF_VIEW_DEGREES / placement.zoom)
				.coerceIn(MINIMUM_VERTICAL_FIELD_OF_VIEW_DEGREES, MAXIMUM_VERTICAL_FIELD_OF_VIEW_DEGREES)
			Matrix.perspectiveM(projection, 0, fieldOfView, aspect, NEAR_PLANE_METERS, far)
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
			mesh.vertices.position(7)
			GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
			GLES20.glVertexAttribPointer(textureCoordinateLocation, 2, GLES20.GL_FLOAT, false, strideBytes, mesh.vertices)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + SATELLITE_TEXTURE_UNIT)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mesh.satelliteTextureId)
			GLES20.glUniform1f(hasSatelliteTextureLocation, if (mesh.satelliteTextureId != 0) 1f else 0f)
			mesh.indices.position(0)
			GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
		}

		private fun clearDefaultFrameBuffer(sky: FloatArray) {
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
			GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
			GLES20.glClearColor(sky[0], sky[1], sky[2], 1f)
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
		}

		private fun createLightMvp(scene: FlightTerrainScene, sun: FlightSunVector): FloatArray {
			val extent = max(MINIMUM_SHADOW_EXTENT_METERS, scene.radiusKm * 1_000f * SHADOW_EXTENT_MULTIPLIER)
			val distance = extent * SHADOW_LIGHT_DISTANCE_MULTIPLIER
			val lightX = sun.east
			val lightY = sun.up
			val lightZ = -sun.north
			val view = FloatArray(16)
			val useAlternateUp = abs(lightY) > 0.92f
			Matrix.setLookAtM(
				view,
				0,
				lightX * distance,
				lightY * distance,
				lightZ * distance,
				0f,
				0f,
				0f,
				0f,
				if (useAlternateUp) 0f else 1f,
				if (useAlternateUp) 1f else 0f
			)
			val projection = FloatArray(16)
			Matrix.orthoM(
				projection,
				0,
				-extent,
				extent,
				-extent,
				extent,
				distance - extent * SHADOW_DEPTH_MULTIPLIER,
				distance + extent * SHADOW_DEPTH_MULTIPLIER
			)
			return FloatArray(16).also { Matrix.multiplyMM(it, 0, projection, 0, view, 0) }
		}

		private fun renderShadowMap(lightMvp: FloatArray) {
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFrameBuffer)
			GLES20.glViewport(0, 0, shadowMapSize, shadowMapSize)
			GLES20.glClearColor(1f, 1f, 1f, 1f)
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
			GLES20.glUseProgram(shadowProgram)
			GLES20.glUniformMatrix4fv(shadowMvpLocation, 1, false, lightMvp, 0)
			GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
			GLES20.glPolygonOffset(SHADOW_POLYGON_OFFSET_FACTOR, SHADOW_POLYGON_OFFSET_UNITS)
			val strideBytes = FlightTerrainMeshBuilder.VERTEX_COMPONENTS * FLOAT_BYTES
			for (mesh in renderMeshes) {
				mesh.vertices.position(0)
				GLES20.glEnableVertexAttribArray(shadowPositionLocation)
				GLES20.glVertexAttribPointer(
					shadowPositionLocation,
					3,
					GLES20.GL_FLOAT,
					false,
					strideBytes,
					mesh.vertices
				)
				mesh.indices.position(0)
				GLES20.glDrawElements(GLES20.GL_TRIANGLES, mesh.indexCount, GLES20.GL_UNSIGNED_SHORT, mesh.indices)
			}
			GLES20.glDisableVertexAttribArray(shadowPositionLocation)
			GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
		}

		private fun shouldUpdateShadowMap(scene: FlightTerrainScene, sun: FlightSunVector): Boolean {
			if (shadowSceneGeneration != scene.generation || shadowSunEast.isNaN()) return true
			val eastDelta = sun.east - shadowSunEast
			val northDelta = sun.north - shadowSunNorth
			val upDelta = sun.up - shadowSunUp
			return eastDelta * eastDelta + northDelta * northDelta + upDelta * upDelta >
				SHADOW_DIRECTION_EPSILON_SQUARED
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

		private fun createShadowResources(): Boolean {
			val maximumTextureSize = IntArray(1)
			val maximumRenderBufferSize = IntArray(1)
			GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maximumTextureSize, 0)
			GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, maximumRenderBufferSize, 0)
			shadowMapSize = min(
				PREFERRED_SHADOW_MAP_SIZE,
				min(maximumTextureSize[0], maximumRenderBufferSize[0])
			)
			if (shadowMapSize < MINIMUM_SHADOW_MAP_SIZE) return false

			val textureIds = IntArray(1)
			GLES20.glGenTextures(1, textureIds, 0)
			shadowTexture = textureIds[0]
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTexture)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
			GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
			GLES20.glTexImage2D(
				GLES20.GL_TEXTURE_2D,
				0,
				GLES20.GL_RGBA,
				shadowMapSize,
				shadowMapSize,
				0,
				GLES20.GL_RGBA,
				GLES20.GL_UNSIGNED_BYTE,
				null
			)

			val depthIds = IntArray(1)
			GLES20.glGenRenderbuffers(1, depthIds, 0)
			shadowDepthBuffer = depthIds[0]
			GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, shadowDepthBuffer)
			GLES20.glRenderbufferStorage(
				GLES20.GL_RENDERBUFFER,
				GLES20.GL_DEPTH_COMPONENT16,
				shadowMapSize,
				shadowMapSize
			)

			val frameBufferIds = IntArray(1)
			GLES20.glGenFramebuffers(1, frameBufferIds, 0)
			shadowFrameBuffer = frameBufferIds[0]
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFrameBuffer)
			GLES20.glFramebufferTexture2D(
				GLES20.GL_FRAMEBUFFER,
				GLES20.GL_COLOR_ATTACHMENT0,
				GLES20.GL_TEXTURE_2D,
				shadowTexture,
				0
			)
			GLES20.glFramebufferRenderbuffer(
				GLES20.GL_FRAMEBUFFER,
				GLES20.GL_DEPTH_ATTACHMENT,
				GLES20.GL_RENDERBUFFER,
				shadowDepthBuffer
			)
			val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) ==
				GLES20.GL_FRAMEBUFFER_COMPLETE
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
			GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
			if (!complete) releaseShadowResources()
			return complete
		}

		private fun releaseShadowResources() {
			if (shadowFrameBuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(shadowFrameBuffer), 0)
			if (shadowDepthBuffer != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(shadowDepthBuffer), 0)
			if (shadowTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(shadowTexture), 0)
			shadowFrameBuffer = 0
			shadowDepthBuffer = 0
			shadowTexture = 0
			shadowMapSize = 0
		}

		private fun createSatelliteTexture(path: String?): Int {
			if (path == null) return 0
			val bitmap = BitmapFactory.decodeFile(path) ?: return 0
			try {
				val textureIds = IntArray(1)
				GLES20.glGenTextures(1, textureIds, 0)
				val textureId = textureIds[0]
				if (textureId == 0) return 0
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
				GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
				val powerOfTwo = isPowerOfTwo(bitmap.width) && isPowerOfTwo(bitmap.height)
				GLES20.glTexParameteri(
					GLES20.GL_TEXTURE_2D,
					GLES20.GL_TEXTURE_MIN_FILTER,
					if (powerOfTwo) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR
				)
				GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
				GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
				GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
				if (powerOfTwo) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
				return textureId
			} finally {
				bitmap.recycle()
			}
		}

		private fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

		private fun releaseRenderMeshes() {
			val textures = renderMeshes.map { it.satelliteTextureId }.filter { it != 0 }.toIntArray()
			if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
			renderMeshes = emptyList()
		}

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
			return RenderMesh(
				vertices = vertexBuffer,
				indices = indexBuffer,
				indexCount = mesh.indices.size,
				satelliteTextureId = createSatelliteTexture(mesh.satelliteTexturePath)
			)
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
			val indexCount: Int,
			val satelliteTextureId: Int
		)

		companion object {
			private const val FLOAT_BYTES = 4
			private const val SHORT_BYTES = 2
			private const val SATELLITE_TEXTURE_UNIT = 0
			private const val SHADOW_TEXTURE_UNIT = 1
			private const val PREFERRED_SHADOW_MAP_SIZE = 2_048
			private const val MINIMUM_SHADOW_MAP_SIZE = 512
			private const val MINIMUM_SHADOW_EXTENT_METERS = 20_000f
			private const val SHADOW_EXTENT_MULTIPLIER = 1.35f
			private const val SHADOW_LIGHT_DISTANCE_MULTIPLIER = 2.6f
			private const val SHADOW_DEPTH_MULTIPLIER = 1.7f
			private const val SHADOW_POLYGON_OFFSET_FACTOR = 2f
			private const val SHADOW_POLYGON_OFFSET_UNITS = 4f
			private const val MINIMUM_SHADOW_SUN_UP = 0.015f
			private const val SHADOW_DIRECTION_EPSILON_SQUARED = 1e-7f
			private const val DEFAULT_FLIGHT_ALTITUDE_METERS = 10_000f
			private const val MINIMUM_GROUND_CLEARANCE_METERS = 60f
			private const val DEFAULT_BEARING_DEGREES = 0f
			private const val MINIMUM_VERTICAL_FIELD_OF_VIEW_DEGREES = 14f
			private const val MAXIMUM_VERTICAL_FIELD_OF_VIEW_DEGREES = 82f
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
				uniform mat4 uLightMvp;
				uniform vec3 uCameraPosition;
				uniform vec3 uLightDirection;
				uniform float uFogDistance;
				uniform float uShadingEnabled;
				attribute vec3 aPosition;
				attribute vec3 aNormal;
				attribute float aElevation;
				attribute vec2 aTexCoord;
				varying float vElevation;
				varying float vLight;
				varying float vNdotL;
				varying float vFog;
				varying float vSlope;
				varying vec2 vTexCoord;
				varying vec4 vShadowPosition;
				void main() {
					vec3 normal = normalize(aNormal);
					vNdotL = max(dot(normal, normalize(uLightDirection)), 0.0);
					float directional = 0.28 + 0.72 * vNdotL;
					vLight = mix(1.0, directional, uShadingEnabled);
					vElevation = aElevation;
					vSlope = 1.0 - clamp(normal.y, 0.0, 1.0);
					vFog = clamp(distance(aPosition, uCameraPosition) / uFogDistance, 0.0, 1.0);
					vTexCoord = aTexCoord;
					vShadowPosition = uLightMvp * vec4(aPosition, 1.0);
					gl_Position = uMvp * vec4(aPosition, 1.0);
				}
			"""

			private const val FRAGMENT_SHADER = """
				#ifdef GL_FRAGMENT_PRECISION_HIGH
				precision highp float;
				#else
				precision mediump float;
				#endif
				uniform vec3 uSkyColor;
				uniform float uDaylight;
				uniform sampler2D uSatelliteTexture;
				uniform float uHasSatelliteTexture;
				uniform sampler2D uShadowMap;
				uniform vec2 uShadowTexelSize;
				uniform float uShadowsEnabled;
				varying float vElevation;
				varying float vLight;
				varying float vNdotL;
				varying float vFog;
				varying float vSlope;
				varying vec2 vTexCoord;
				varying vec4 vShadowPosition;

				float unpackDepth(vec4 encodedDepth) {
					const vec4 bitShift = vec4(
						1.0 / 16777216.0,
						1.0 / 65536.0,
						1.0 / 256.0,
						1.0
					);
					return dot(encodedDepth, bitShift);
				}

				float shadowVisibility() {
					if (uShadowsEnabled < 0.5) return 1.0;
					vec3 projected = vShadowPosition.xyz / vShadowPosition.w;
					projected = projected * 0.5 + 0.5;
					if (projected.x <= 0.0 || projected.x >= 1.0 ||
						projected.y <= 0.0 || projected.y >= 1.0 ||
						projected.z <= 0.0 || projected.z >= 1.0) return 1.0;
					float bias = mix(0.00042, 0.00008, clamp(vNdotL, 0.0, 1.0));
					float currentDepth = projected.z - bias;
					float visible = 0.0;
					for (int y = -1; y <= 1; y++) {
						for (int x = -1; x <= 1; x++) {
							vec2 offset = vec2(float(x), float(y)) * uShadowTexelSize;
							float storedDepth = unpackDepth(texture2D(uShadowMap, projected.xy + offset));
							visible += step(currentDepth, storedDepth);
						}
					}
					return mix(0.34, 1.0, visible / 9.0);
				}

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
					vec3 procedural = mix(water, terrain, step(0.0, vElevation));
					vec3 satellite = texture2D(uSatelliteTexture, vTexCoord).rgb;
					vec3 base = mix(procedural, satellite, uHasSatelliteTexture * 0.96);
					float castShadow = shadowVisibility();
					vec3 lit = base * vLight * castShadow * mix(0.08, 1.0, uDaylight);
					float fogAmount = smoothstep(0.68, 1.0, vFog);
					gl_FragColor = vec4(mix(lit, uSkyColor, fogAmount), 1.0);
				}
			"""

			private const val SHADOW_VERTEX_SHADER = """
				uniform mat4 uLightMvp;
				attribute vec3 aPosition;
				void main() {
					gl_Position = uLightMvp * vec4(aPosition, 1.0);
				}
			"""

			private const val SHADOW_FRAGMENT_SHADER = """
				#ifdef GL_FRAGMENT_PRECISION_HIGH
				precision highp float;
				#else
				precision mediump float;
				#endif
				vec4 packDepth(float depth) {
					const vec4 bitShift = vec4(16777216.0, 65536.0, 256.0, 1.0);
					const vec4 bitMask = vec4(0.0, 1.0 / 256.0, 1.0 / 256.0, 1.0 / 256.0);
					vec4 encodedDepth = fract(min(depth, 0.999999) * bitShift);
					encodedDepth -= encodedDepth.xxyz * bitMask;
					return encodedDepth;
				}
				void main() {
					gl_FragColor = packDepth(gl_FragCoord.z);
				}
			"""
		}
	}
}
