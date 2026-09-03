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
import java.util.LinkedHashMap
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

	private var rendererErrorListener: ((String) -> Unit)? = null
	private var renderStatsListener: ((FlightTerrainRenderStats) -> Unit)? = null
	private val terrainRenderer = TerrainRenderer(
		onError = { message -> post { rendererErrorListener?.invoke(message) } },
		onStats = { stats -> post { renderStatsListener?.invoke(stats) } },
		requestFrame = { post { requestRender() } }
	)

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
		windowLook: FlightWindowLook,
		altitudeOverrideMeters: Float?,
		shadingEnabled: Boolean,
		shadowIntensity: Float,
		satelliteOpacity: Float,
		showSatelliteQualityOverlay: Boolean,
		terrainOpacity: Float,
		nativeMapOpacity: Float,
		onRendererError: (String) -> Unit,
		onRenderStats: (FlightTerrainRenderStats) -> Unit
	) {
		rendererErrorListener = onRendererError
		renderStatsListener = onRenderStats
		terrainRenderer.update(
			scene,
			sample,
			windowPlacement,
			windowLook,
			altitudeOverrideMeters,
			shadingEnabled,
			shadowIntensity,
			satelliteOpacity,
			showSatelliteQualityOverlay,
			terrainOpacity,
			nativeMapOpacity
		)
		requestRender()
	}

	private class TerrainRenderer(
		private val onError: (String) -> Unit,
		private val onStats: (FlightTerrainRenderStats) -> Unit,
		private val requestFrame: () -> Unit
	) : GLSurfaceView.Renderer {

		@Volatile
		private var scene: FlightTerrainScene? = null
		@Volatile
		private var sample: FlightSample? = null
		@Volatile
		private var windowPlacement: FlightWindowPlacement = FlightWindowPlacement()
		@Volatile
		private var windowLook: FlightWindowLook = FlightWindowLook()
		@Volatile
		private var altitudeOverrideMeters: Float? = null
		@Volatile
		private var shadingEnabled: Boolean = true
		@Volatile
		private var shadowIntensity: Float = 0.85f
		@Volatile
		private var satelliteOpacity: Float = 0.92f
		@Volatile
		private var showSatelliteQualityOverlay: Boolean = false
		@Volatile
		private var terrainOpacity: Float = 0.70f
		@Volatile
		private var nativeMapOpacity: Float = 0.58f

		private var program = 0
		private var shadowProgram = 0
		private var surfaceWidth = 1
		private var surfaceHeight = 1
		private var uploadedGeneration = Long.MIN_VALUE
		private var renderMeshes: List<RenderMesh> = emptyList()
		private val geometryCache = LinkedHashMap<TerrainTileId, CachedGeometry>(
			MAXIMUM_RENDER_GEOMETRIES + 1,
			0.75f,
			true
		)
		private val textureCache = LinkedHashMap<String, UploadedTexture>(32, 0.75f, true)
		private val pendingTextureUploads = linkedSetOf<String>()
		private var lastReportedStats: FlightTerrainRenderStats? = null
		private var lastStatsReportNanos = 0L

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
		private var satelliteOpacityLocation = -1
		private var qualityDebugEnabledLocation = -1
		private var qualityDebugColorLocation = -1
		private var terrainOpacityLocation = -1
		private var terrainReadyLocation = -1
		private var nativeMapTextureLocation = -1
		private var hasNativeMapTextureLocation = -1
		private var nativeMapOpacityLocation = -1
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
		private var shadowFocusX = Float.NaN
		private var shadowFocusY = Float.NaN
		private var shadowFocusZ = Float.NaN
		private var shadowLightMvp = FloatArray(16)

		fun update(
			scene: FlightTerrainScene?,
			sample: FlightSample?,
			windowPlacement: FlightWindowPlacement,
			windowLook: FlightWindowLook,
			altitudeOverrideMeters: Float?,
			shadingEnabled: Boolean,
			shadowIntensity: Float,
			satelliteOpacity: Float,
			showSatelliteQualityOverlay: Boolean,
			terrainOpacity: Float,
			nativeMapOpacity: Float
		) {
			this.scene = scene
			this.sample = sample
			this.windowPlacement = windowPlacement.clamped()
			this.windowLook = windowLook.clamped()
			this.altitudeOverrideMeters = altitudeOverrideMeters
			this.shadingEnabled = shadingEnabled
			this.shadowIntensity = shadowIntensity.coerceIn(0f, 1f)
			this.satelliteOpacity = satelliteOpacity.coerceIn(0f, 1f)
			this.showSatelliteQualityOverlay = showSatelliteQualityOverlay
			this.terrainOpacity = terrainOpacity.coerceIn(0f, 1f)
			this.nativeMapOpacity = nativeMapOpacity.coerceIn(0f, 1f)
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
				satelliteOpacityLocation = GLES20.glGetUniformLocation(program, "uSatelliteOpacity")
				qualityDebugEnabledLocation = GLES20.glGetUniformLocation(program, "uQualityDebugEnabled")
				qualityDebugColorLocation = GLES20.glGetUniformLocation(program, "uQualityDebugColor")
				terrainOpacityLocation = GLES20.glGetUniformLocation(program, "uTerrainOpacity")
				terrainReadyLocation = GLES20.glGetUniformLocation(program, "uTerrainReady")
				nativeMapTextureLocation = GLES20.glGetUniformLocation(program, "uNativeMapTexture")
				hasNativeMapTextureLocation = GLES20.glGetUniformLocation(program, "uHasNativeMapTexture")
				nativeMapOpacityLocation = GLES20.glGetUniformLocation(program, "uNativeMapOpacity")
				shadowTextureLocation = GLES20.glGetUniformLocation(program, "uShadowMap")
				shadowTexelSizeLocation = GLES20.glGetUniformLocation(program, "uShadowTexelSize")
				shadowsEnabledLocation = GLES20.glGetUniformLocation(program, "uShadowsEnabled")
				shadowPositionLocation = GLES20.glGetAttribLocation(shadowProgram, "aPosition")
				shadowMvpLocation = GLES20.glGetUniformLocation(shadowProgram, "uLightMvp")
				shadowAvailable = createShadowResources()
				uploadedGeneration = Long.MIN_VALUE
				renderMeshes = emptyList()
				geometryCache.clear()
				textureCache.clear()
				pendingTextureUploads.clear()
				lastReportedStats = null
				lastStatsReportNanos = 0L
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
			val currentWindowLook = windowLook
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
				try {
					replaceRenderMeshes(currentScene.meshes)
					uploadedGeneration = currentScene.generation
				} catch (error: RuntimeException) {
					releaseRenderMeshes()
					uploadedGeneration = currentScene.generation
					onError(error.message ?: "Mise à jour GPU du relief impossible")
					clearDefaultFrameBuffer(sky)
					return
				}
			}
			processTextureUploads()
			publishRenderStats()
			if (renderMeshes.isEmpty()) {
				clearDefaultFrameBuffer(sky)
				if (pendingTextureUploads.isNotEmpty()) requestFrame()
				return
			}

			val ground = currentScene.centerGroundElevationMeters ?: 0f
			val reportedAltitude = altitudeOverrideMeters
				?: currentSample?.altitudeMeters?.toFloat()
				?: DEFAULT_FLIGHT_ALTITUDE_METERS
			val altitude = max(reportedAltitude, ground + MINIMUM_GROUND_CLEARANCE_METERS)
			val coordinates = FlightTerrainCoordinates(
				currentScene.coordinateOriginLatitude,
				currentScene.coordinateOriginLongitude
			)
			val camera = coordinates.toLocal(latitude, longitude, altitude.toDouble())
			val groundFocus = coordinates.toLocal(latitude, longitude, 0.0)
			val lightDirection = coordinates.vectorToLocal(
				latitude,
				longitude,
				sun.east,
				sun.up,
				-sun.north
			)

			// Cast shadows are intentionally local to the aircraft. A single shadow map
			// stretched over the whole 300 km scene had too little precision and exposed
			// its moving projection boundary as a false east/west "night" line.
			val lightMvp = createLightMvp(
				currentScene,
				lightDirection,
				groundFocus[0],
				groundFocus[1],
				groundFocus[2]
			)
			val shadowStrength = if (shadingEnabled && shadowAvailable) {
				((sun.up - MINIMUM_SHADOW_SUN_UP) / SHADOW_FADE_SUN_RANGE).coerceIn(0f, 1f) *
					shadowIntensity
			} else 0f
			val shadowsActive = shadowStrength > 0f
			if (shadowsActive && shouldUpdateShadowMap(
					currentScene,
					lightDirection,
					groundFocus[0],
					groundFocus[1],
					groundFocus[2]
				)
			) {
				renderShadowMap(lightMvp)
				lightMvp.copyInto(shadowLightMvp)
				shadowSceneGeneration = currentScene.geometryGeneration
				shadowSunEast = lightDirection[0]
				shadowSunNorth = lightDirection[2]
				shadowSunUp = lightDirection[1]
				shadowFocusX = groundFocus[0]
				shadowFocusY = groundFocus[1]
				shadowFocusZ = groundFocus[2]
			}
			clearDefaultFrameBuffer(sky)
			val bearing = currentSample?.bearingDegrees ?: DEFAULT_BEARING_DEGREES
			val geometry = currentWindowPlacement.geometry()
			val viewAzimuth = Math.toRadians(
				currentWindowPlacement.viewAzimuthDegrees(bearing, currentWindowLook).toDouble()
			)
			val viewElevation = (geometry.elevationRadians + Math.toRadians(currentWindowLook.pitchDegrees.toDouble()))
				.coerceIn(Math.toRadians(-89.0), Math.toRadians(45.0))
			val horizontalDirection = cos(viewElevation)
			val directionX = (sin(viewAzimuth) * horizontalDirection).toFloat()
			val directionY = sin(viewElevation).toFloat()
			val directionZ = (-cos(viewAzimuth) * horizontalDirection).toFloat()
			val viewDirection = coordinates.vectorToLocal(
				latitude,
				longitude,
				directionX,
				directionY,
				directionZ
			)
			val cameraUp = coordinates.vectorToLocal(latitude, longitude, 0f, 1f, 0f)

			val view = FloatArray(16)
			Matrix.setLookAtM(
				view,
				0,
				camera[0], camera[1], camera[2],
				camera[0] + viewDirection[0],
				camera[1] + viewDirection[1],
				camera[2] + viewDirection[2],
				cameraUp[0], cameraUp[1], cameraUp[2]
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
			GLES20.glUniform3f(lightLocation, lightDirection[0], lightDirection[1], lightDirection[2])
			GLES20.glUniform1f(fogDistanceLocation, currentScene.radiusKm * 1_000f * 0.92f)
			// Keep directional relief lighting deliberately subtle. Cast-shadow strength
			// is controlled separately below; using the full shadow slider here used to
			// dim every slope and made the toggle look like a global dark overlay.
			GLES20.glUniform1f(
				shadingLocation,
				if (shadingEnabled) RELIEF_LIGHTING_STRENGTH * shadowIntensity else 0f
			)
			GLES20.glUniform3f(skyColorLocation, sky[0], sky[1], sky[2])
			GLES20.glUniform1f(daylightLocation, daylight)
			GLES20.glUniform1f(satelliteOpacityLocation, satelliteOpacity)
			GLES20.glUniform1f(qualityDebugEnabledLocation, if (showSatelliteQualityOverlay) 1f else 0f)
			GLES20.glUniform1f(terrainOpacityLocation, terrainOpacity)
			GLES20.glUniform1f(nativeMapOpacityLocation, nativeMapOpacity)
			GLES20.glUniform1f(shadowsEnabledLocation, shadowStrength)
			GLES20.glUniform2f(
				shadowTexelSizeLocation,
				if (shadowMapSize > 0) 1f / shadowMapSize else 0f,
				if (shadowMapSize > 0) 1f / shadowMapSize else 0f
			)
			GLES20.glUniform1i(satelliteTextureLocation, SATELLITE_TEXTURE_UNIT)
			GLES20.glUniform1i(nativeMapTextureLocation, NATIVE_MAP_TEXTURE_UNIT)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + SHADOW_TEXTURE_UNIT)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (shadowsActive) shadowTexture else 0)
			GLES20.glUniform1i(shadowTextureLocation, SHADOW_TEXTURE_UNIT)
			for (mesh in renderMeshes) drawMesh(mesh)
			GLES20.glDisableVertexAttribArray(positionLocation)
			GLES20.glDisableVertexAttribArray(normalLocation)
			GLES20.glDisableVertexAttribArray(elevationLocation)
			GLES20.glDisableVertexAttribArray(textureCoordinateLocation)
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + SHADOW_TEXTURE_UNIT)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + NATIVE_MAP_TEXTURE_UNIT)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
			if (pendingTextureUploads.isNotEmpty()) requestFrame()
		}

		private fun windowProjection(placement: FlightWindowPlacement, radiusKm: Int): FloatArray {
			val projection = FloatArray(16)
			val aspect = surfaceWidth.toFloat() / surfaceHeight
			val far = max(MINIMUM_FAR_PLANE_METERS, radiusKm * 1_000f * 2.2f)
			val fieldOfView = placement.verticalFieldOfViewDegrees()
			Matrix.perspectiveM(projection, 0, fieldOfView, aspect, NEAR_PLANE_METERS, far)
			return projection
		}

		private fun drawMesh(mesh: RenderMesh) {
			val strideBytes = FlightTerrainMeshBuilder.VERTEX_COMPONENTS * FLOAT_BYTES
			val geometry = mesh.geometry
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, geometry.vertexBufferId)
			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, geometry.indexBufferId)
			GLES20.glEnableVertexAttribArray(positionLocation)
			GLES20.glVertexAttribPointer(positionLocation, 3, GLES20.GL_FLOAT, false, strideBytes, 0)
			GLES20.glEnableVertexAttribArray(normalLocation)
			GLES20.glVertexAttribPointer(
				normalLocation,
				3,
				GLES20.GL_FLOAT,
				false,
				strideBytes,
				3 * FLOAT_BYTES
			)
			GLES20.glEnableVertexAttribArray(elevationLocation)
			GLES20.glVertexAttribPointer(
				elevationLocation,
				1,
				GLES20.GL_FLOAT,
				false,
				strideBytes,
				6 * FLOAT_BYTES
			)
			GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
			GLES20.glVertexAttribPointer(
				textureCoordinateLocation,
				2,
				GLES20.GL_FLOAT,
				false,
				strideBytes,
				7 * FLOAT_BYTES
			)
			val detailedTextureId = mesh.satelliteTexturePath?.let { textureCache[it]?.id } ?: 0
			val standardTextureId = mesh.standardSatelliteTexturePath?.let { textureCache[it]?.id } ?: 0
			val satelliteTextureId = if (detailedTextureId != 0) {
				detailedTextureId
			} else if (mesh.satelliteTextureTier != FlightTerrainTextureTier.OVERVIEW) {
				standardTextureId
			} else 0
			val activeTextureTier = when {
				detailedTextureId != 0 -> mesh.satelliteTextureTier
				standardTextureId != 0 && mesh.satelliteTextureTier != FlightTerrainTextureTier.OVERVIEW ->
					FlightTerrainTextureTier.STANDARD
				else -> FlightTerrainTextureTier.OVERVIEW
			}
			val qualityColor = qualityDebugColor(activeTextureTier)
			GLES20.glUniform3f(
				qualityDebugColorLocation,
				qualityColor[0],
				qualityColor[1],
				qualityColor[2]
			)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + SATELLITE_TEXTURE_UNIT)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, satelliteTextureId)
			GLES20.glUniform1f(hasSatelliteTextureLocation, if (satelliteTextureId != 0) 1f else 0f)
			val nativeMapTextureId = mesh.nativeMapTexturePath?.let { textureCache[it]?.id } ?: 0
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + NATIVE_MAP_TEXTURE_UNIT)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nativeMapTextureId)
			GLES20.glUniform1f(hasNativeMapTextureLocation, if (nativeMapTextureId != 0) 1f else 0f)
			GLES20.glUniform1f(terrainReadyLocation, if (mesh.terrainAvailable) 1f else 0f)
			GLES20.glDrawElements(GLES20.GL_TRIANGLES, geometry.indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
		}

		private fun qualityDebugColor(tier: FlightTerrainTextureTier): FloatArray = when (tier) {
			FlightTerrainTextureTier.OVERVIEW -> QUALITY_DEBUG_OVERVIEW
			FlightTerrainTextureTier.STANDARD -> QUALITY_DEBUG_STANDARD
			FlightTerrainTextureTier.HIGH -> QUALITY_DEBUG_HIGH
			FlightTerrainTextureTier.ULTRA -> QUALITY_DEBUG_ULTRA
			FlightTerrainTextureTier.ULTRA_PLUS -> QUALITY_DEBUG_ULTRA_PLUS
		}

		private fun clearDefaultFrameBuffer(sky: FloatArray) {
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
			GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
			GLES20.glClearColor(sky[0], sky[1], sky[2], 1f)
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
		}

		private fun createLightMvp(
			scene: FlightTerrainScene,
			lightDirection: FloatArray,
			focusX: Float,
			focusY: Float,
			focusZ: Float
		): FloatArray {
			val extent = (scene.radiusKm * 1_000f * SHADOW_EXTENT_MULTIPLIER)
				.coerceIn(MINIMUM_SHADOW_EXTENT_METERS, MAXIMUM_SHADOW_EXTENT_METERS)
			val distance = extent * SHADOW_LIGHT_DISTANCE_MULTIPLIER
			val lightX = lightDirection[0]
			val lightY = lightDirection[1]
			val lightZ = lightDirection[2]
			val view = FloatArray(16)
			val useAlternateUp = abs(lightY) > 0.92f
			Matrix.setLookAtM(
				view,
				0,
				focusX + lightX * distance,
				focusY + lightY * distance,
				focusZ + lightZ * distance,
				focusX,
				focusY,
				focusZ,
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
				val geometry = mesh.geometry
				GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, geometry.vertexBufferId)
				GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, geometry.indexBufferId)
				GLES20.glEnableVertexAttribArray(shadowPositionLocation)
				GLES20.glVertexAttribPointer(
					shadowPositionLocation,
					3,
					GLES20.GL_FLOAT,
					false,
					strideBytes,
					0
				)
				GLES20.glDrawElements(
					GLES20.GL_TRIANGLES,
					geometry.indexCount,
					GLES20.GL_UNSIGNED_SHORT,
					0
				)
			}
			GLES20.glDisableVertexAttribArray(shadowPositionLocation)
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
			GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
		}

		private fun shouldUpdateShadowMap(
			scene: FlightTerrainScene,
			lightDirection: FloatArray,
			focusX: Float,
			focusY: Float,
			focusZ: Float
		): Boolean {
			if (shadowSceneGeneration != scene.geometryGeneration || shadowSunEast.isNaN()) return true
			val focusDeltaX = focusX - shadowFocusX
			val focusDeltaY = focusY - shadowFocusY
			val focusDeltaZ = focusZ - shadowFocusZ
			if (focusDeltaX * focusDeltaX + focusDeltaY * focusDeltaY + focusDeltaZ * focusDeltaZ >
				SHADOW_FOCUS_UPDATE_DISTANCE_SQUARED
			) {
				return true
			}
			val eastDelta = lightDirection[0] - shadowSunEast
			val northDelta = lightDirection[2] - shadowSunNorth
			val upDelta = lightDirection[1] - shadowSunUp
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

		private fun createTexture(path: String): UploadedTexture? {
			val bitmap = BitmapFactory.decodeFile(
				path,
				BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
			) ?: return null
			try {
				val textureIds = IntArray(1)
				GLES20.glGenTextures(1, textureIds, 0)
				val textureId = textureIds[0]
				if (textureId == 0) return null
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
				val baseBytes = bitmap.width.toLong() * bitmap.height * RGB_565_BYTES_PER_PIXEL
				return UploadedTexture(
					id = textureId,
					bytes = if (powerOfTwo) baseBytes * 4L / 3L else baseBytes
				)
			} finally {
				bitmap.recycle()
			}
		}

		private fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

		private fun releaseRenderMeshes() {
			val textures = textureCache.values.map { it.id }.filter { it != 0 }.distinct().toIntArray()
			if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
			geometryCache.values.forEach(::releaseGeometry)
			renderMeshes = emptyList()
			geometryCache.clear()
			textureCache.clear()
			pendingTextureUploads.clear()
		}

		private fun replaceRenderMeshes(meshes: List<FlightTerrainMesh>) {
			val activeTileIds = meshes.mapTo(hashSetOf()) { it.tileId }
			renderMeshes = meshes.map { mesh ->
				RenderMesh(
					geometry = cachedGeometry(mesh),
					terrainAvailable = mesh.terrainAvailable,
					satelliteTexturePath = mesh.satelliteTexturePath,
					standardSatelliteTexturePath = mesh.standardSatelliteTexturePath,
					satelliteTextureTier = mesh.satelliteTextureTier,
					nativeMapTexturePath = mesh.nativeMapTexturePath
				)
			}
			evictGeometryCache(activeTileIds)

			val wantedPaths = renderMeshes.flatMapTo(linkedSetOf()) { mesh ->
				listOfNotNull(
					mesh.standardSatelliteTexturePath,
					mesh.satelliteTexturePath,
					mesh.nativeMapTexturePath
				)
			}
			pendingTextureUploads.retainAll(wantedPaths)
			// Near tiles arrive first. Standard is queued before its detailed replacement,
			// so the screen never waits for a 1024/2048 px upload to gain useful imagery.
			renderMeshes.forEach { mesh ->
				if (mesh.satelliteTextureTier != FlightTerrainTextureTier.OVERVIEW) {
					enqueueTexture(mesh.standardSatelliteTexturePath)
					enqueueTexture(mesh.satelliteTexturePath)
				}
				enqueueTexture(mesh.nativeMapTexturePath)
			}
			// Warm Standard for overview tiles after every visible texture. A later quality
			// change can then switch handles without decoding the complete scene at once.
			renderMeshes.forEach { mesh -> enqueueTexture(mesh.standardSatelliteTexturePath) }
			evictTextureCache(wantedPaths)
		}

		private fun cachedGeometry(mesh: FlightTerrainMesh): CachedGeometry {
			val cached = geometryCache[mesh.tileId]
			if (cached != null && cached.sourceVertices === mesh.vertices && cached.sourceIndices === mesh.indices) {
				return cached
			}
			if (cached != null) releaseGeometry(cached)
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
			val bufferIds = IntArray(2)
			GLES20.glGenBuffers(bufferIds.size, bufferIds, 0)
			if (bufferIds.any { it == 0 }) {
				val validIds = bufferIds.filter { it != 0 }.toIntArray()
				if (validIds.isNotEmpty()) GLES20.glDeleteBuffers(validIds.size, validIds, 0)
				throw IllegalStateException("Impossible de créer les buffers GPU du relief")
			}
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bufferIds[0])
			GLES20.glBufferData(
				GLES20.GL_ARRAY_BUFFER,
				mesh.vertices.size * FLOAT_BYTES,
				vertexBuffer,
				GLES20.GL_STATIC_DRAW
			)
			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufferIds[1])
			GLES20.glBufferData(
				GLES20.GL_ELEMENT_ARRAY_BUFFER,
				mesh.indices.size * SHORT_BYTES,
				indexBuffer,
				GLES20.GL_STATIC_DRAW
			)
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
			return CachedGeometry(
				tileId = mesh.tileId,
				sourceVertices = mesh.vertices,
				sourceIndices = mesh.indices,
				vertexBufferId = bufferIds[0],
				indexBufferId = bufferIds[1],
				indexCount = mesh.indices.size,
				bytes = mesh.vertices.size.toLong() * FLOAT_BYTES + mesh.indices.size.toLong() * SHORT_BYTES
			).also { geometryCache[mesh.tileId] = it }
		}

		private fun enqueueTexture(path: String?) {
			if (path != null && path !in textureCache) pendingTextureUploads += path
		}

		private fun processTextureUploads() {
			var uploadedCount = 0
			var uploadedBytes = 0L
			val iterator = pendingTextureUploads.iterator()
			while (iterator.hasNext() && uploadedCount < MAXIMUM_TEXTURE_UPLOADS_PER_FRAME) {
				val path = iterator.next()
				if (path in textureCache) {
					iterator.remove()
					continue
				}
				val estimatedBytes = estimatedTextureFileBytes(path)
				if (uploadedCount > 0 && uploadedBytes + estimatedBytes > MAXIMUM_TEXTURE_UPLOAD_BYTES_PER_FRAME) break
				iterator.remove()
				createTexture(path)?.let { uploaded ->
					textureCache[path] = uploaded
					uploadedBytes += uploaded.bytes
				}
				uploadedCount++
			}
			val protectedPaths = renderMeshes.flatMapTo(hashSetOf()) { mesh ->
				listOfNotNull(
					mesh.satelliteTexturePath,
					mesh.standardSatelliteTexturePath,
					mesh.nativeMapTexturePath
				)
			}
			evictTextureCache(protectedPaths)
		}

		private fun estimatedTextureFileBytes(path: String): Long {
			val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(path, options)
			if (options.outWidth <= 0 || options.outHeight <= 0) return 0L
			val base = options.outWidth.toLong() * options.outHeight * RGB_565_BYTES_PER_PIXEL
			return if (isPowerOfTwo(options.outWidth) && isPowerOfTwo(options.outHeight)) base * 4L / 3L else base
		}

		private fun evictGeometryCache(activeTileIds: Set<TerrainTileId>) {
			var totalBytes = geometryCache.values.sumOf { it.bytes }
			while (geometryCache.size > MAXIMUM_RENDER_GEOMETRIES ||
				totalBytes > MAXIMUM_RENDER_GEOMETRY_BYTES
			) {
				val candidate = geometryCache.entries.firstOrNull { it.key !in activeTileIds } ?: break
				geometryCache.remove(candidate.key)?.let { geometry ->
					totalBytes -= geometry.bytes
					releaseGeometry(geometry)
				}
			}
		}

		private fun releaseGeometry(geometry: CachedGeometry) {
			val ids = intArrayOf(geometry.vertexBufferId, geometry.indexBufferId).filter { it != 0 }.toIntArray()
			if (ids.isNotEmpty()) GLES20.glDeleteBuffers(ids.size, ids, 0)
		}

		private fun evictTextureCache(protectedPaths: Set<String>) {
			var totalBytes = textureCache.values.sumOf { it.bytes }
			if (totalBytes <= MAXIMUM_TEXTURE_CACHE_BYTES) return
			val iterator = textureCache.entries.iterator()
			while (iterator.hasNext() && totalBytes > MAXIMUM_TEXTURE_CACHE_BYTES) {
				val entry = iterator.next()
				if (entry.key in protectedPaths) continue
				GLES20.glDeleteTextures(1, intArrayOf(entry.value.id), 0)
				totalBytes -= entry.value.bytes
				iterator.remove()
			}
		}

		private fun publishRenderStats() {
			val stats = FlightTerrainRenderStats(
				visibleMeshes = renderMeshes.size,
				cachedGeometryTiles = geometryCache.size,
				cachedTextures = textureCache.size,
				queuedTextureUploads = pendingTextureUploads.size,
				geometryBytes = geometryCache.values.sumOf { it.bytes },
				textureBytes = textureCache.values.sumOf { it.bytes }
			)
			val now = System.nanoTime()
			val queueJustCompleted = stats.queuedTextureUploads == 0 &&
				(lastReportedStats?.queuedTextureUploads ?: 0) > 0
			if (stats != lastReportedStats &&
				(lastReportedStats == null || queueJustCompleted || now - lastStatsReportNanos >= STATS_REPORT_INTERVAL_NANOS)
			) {
				lastReportedStats = stats
				lastStatsReportNanos = now
				onStats(stats)
			}
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

		private data class CachedGeometry(
			val tileId: TerrainTileId,
			val sourceVertices: FloatArray,
			val sourceIndices: ShortArray,
			val vertexBufferId: Int,
			val indexBufferId: Int,
			val indexCount: Int,
			val bytes: Long
		)

		private data class UploadedTexture(
			val id: Int,
			val bytes: Long
		)

		private data class RenderMesh(
			val geometry: CachedGeometry,
			val terrainAvailable: Boolean,
			val satelliteTexturePath: String?,
			val standardSatelliteTexturePath: String?,
			val satelliteTextureTier: FlightTerrainTextureTier,
			val nativeMapTexturePath: String?
		)

		companion object {
			private const val FLOAT_BYTES = 4
			private const val SHORT_BYTES = 2
			private const val RGB_565_BYTES_PER_PIXEL = 2L
			private const val MAXIMUM_RENDER_GEOMETRIES = 768
			private const val MAXIMUM_RENDER_GEOMETRY_BYTES = 96L * 1_024L * 1_024L
			private const val MAXIMUM_TEXTURE_CACHE_BYTES = 128L * 1_024L * 1_024L
			private const val MAXIMUM_TEXTURE_UPLOADS_PER_FRAME = 2
			private const val MAXIMUM_TEXTURE_UPLOAD_BYTES_PER_FRAME = 8L * 1_024L * 1_024L
			private const val STATS_REPORT_INTERVAL_NANOS = 250_000_000L
			private const val SATELLITE_TEXTURE_UNIT = 0
			private const val SHADOW_TEXTURE_UNIT = 1
			private const val NATIVE_MAP_TEXTURE_UNIT = 2
			private const val PREFERRED_SHADOW_MAP_SIZE = 2_048
			private const val MINIMUM_SHADOW_MAP_SIZE = 512
			private const val MINIMUM_SHADOW_EXTENT_METERS = 20_000f
			private const val MAXIMUM_SHADOW_EXTENT_METERS = 140_000f
			private const val SHADOW_EXTENT_MULTIPLIER = 0.55f
			private const val SHADOW_LIGHT_DISTANCE_MULTIPLIER = 2.6f
			private const val SHADOW_DEPTH_MULTIPLIER = 2.2f
			private const val SHADOW_POLYGON_OFFSET_FACTOR = 2f
			private const val SHADOW_POLYGON_OFFSET_UNITS = 4f
			private const val RELIEF_LIGHTING_STRENGTH = 0.18f
			private const val MINIMUM_SHADOW_SUN_UP = 0.015f
			private const val SHADOW_FADE_SUN_RANGE = 0.10f
			private const val SHADOW_DIRECTION_EPSILON_SQUARED = 1e-7f
			private const val SHADOW_FOCUS_UPDATE_DISTANCE_SQUARED = 4_000f * 4_000f
			private const val DEFAULT_FLIGHT_ALTITUDE_METERS = 10_000f
			private const val MINIMUM_GROUND_CLEARANCE_METERS = 60f
			private const val DEFAULT_BEARING_DEGREES = 0f
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
			private val QUALITY_DEBUG_OVERVIEW = floatArrayOf(0.28f, 0.33f, 0.40f)
			private val QUALITY_DEBUG_STANDARD = floatArrayOf(0.18f, 0.83f, 0.75f)
			private val QUALITY_DEBUG_HIGH = floatArrayOf(1.00f, 0.78f, 0.25f)
			private val QUALITY_DEBUG_ULTRA = floatArrayOf(1.00f, 0.34f, 0.18f)
			private val QUALITY_DEBUG_ULTRA_PLUS = floatArrayOf(0.70f, 0.34f, 1.00f)
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
					// Ambient light stays dominant. Terrain occlusion is handled by the
					// shadow map, not by globally blackening every face away from the sun.
					float directional = 0.78 + 0.22 * vNdotL;
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
				uniform float uSatelliteOpacity;
				uniform float uQualityDebugEnabled;
				uniform vec3 uQualityDebugColor;
				uniform float uTerrainOpacity;
				uniform float uTerrainReady;
				uniform sampler2D uNativeMapTexture;
				uniform float uHasNativeMapTexture;
				uniform float uNativeMapOpacity;
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
					return encodedDepth.r + encodedDepth.g / 255.0;
				}

				float shadowVisibility() {
					if (uShadowsEnabled <= 0.001) return 1.0;
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
					// Preserve enough ambient light inside a cast shadow to keep satellite
					// imagery readable; only shadow-map occlusion receives this attenuation.
					float realShadow = mix(0.52, 1.0, visible / 9.0);
					float edgeDistance = min(
						min(
							min(projected.x, 1.0 - projected.x),
							min(projected.y, 1.0 - projected.y)
						),
						min(projected.z, 1.0 - projected.z)
					);
					// Fade a wide band around the local map instead of exposing a hard
					// projection edge across the landscape.
					float edgeBlend = smoothstep(0.08, 0.22, edgeDistance);
					return mix(1.0, mix(1.0, realShadow, edgeBlend), clamp(uShadowsEnabled, 0.0, 1.0));
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
					// Missing MNT tiles remain visible without pretending that unknown land is
					// ocean or vegetation. Satellite imagery can still cover this neutral base.
					procedural = mix(vec3(0.20, 0.23, 0.24), procedural, uTerrainReady);
					vec3 satellite = texture2D(uSatelliteTexture, vTexCoord).rgb;
					float terrainWeight = max(uTerrainOpacity, 0.001);
					float satelliteWeight = uHasSatelliteTexture * uSatelliteOpacity;
					vec3 base = (procedural * terrainWeight + satellite * satelliteWeight) /
						max(terrainWeight + satelliteWeight, 0.001);
					vec3 nativeMap = texture2D(uNativeMapTexture, vTexCoord).rgb;
					base = mix(base, nativeMap, uHasNativeMapTexture * uNativeMapOpacity);
					float castShadow = shadowVisibility();
					vec3 naturalLit = base * vLight * castShadow * mix(0.08, 1.0, uDaylight);
					float fogAmount = smoothstep(0.68, 1.0, vFog);
					vec3 natural = mix(naturalLit, uSkyColor, fogAmount);
					// Diagnostic colours describe what is actually bound on the GPU and must
					// remain readable at night and through distant fog. A tiny normal term
					// preserves the terrain shape.
					vec3 diagnostic = uQualityDebugColor * (0.86 + 0.14 * vNdotL);
					gl_FragColor = vec4(mix(natural, diagnostic, uQualityDebugEnabled), 1.0);
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
					// Two-channel 16-bit packing matches the depth buffer and stays safe
					// on mobile GPUs whose fragment highp silently falls back to mediump.
					vec2 encodedDepth = fract(vec2(1.0, 255.0) * min(depth, 0.99999));
					encodedDepth.x -= encodedDepth.y / 255.0;
					return vec4(encodedDepth, 0.0, 1.0);
				}
				void main() {
					gl_FragColor = packDepth(gl_FragCoord.z);
				}
			"""
		}
	}
}
