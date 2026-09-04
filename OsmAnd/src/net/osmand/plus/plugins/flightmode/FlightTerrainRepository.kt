package net.osmand.plus.plugins.flightmode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.osmand.osm.io.NetworkUtils
import net.osmand.plus.OsmandApplication
import net.osmand.plus.Version
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

class FlightTerrainRepository(private val app: OsmandApplication) {

	private val terrainDirectory = File(app.filesDir, TERRAIN_DIRECTORY)
	private val satelliteDirectory = File(app.filesDir, FlightSatelliteSource.CACHE_DIRECTORY)
	private val satelliteRenderDirectory = File(app.filesDir, FlightSatelliteSource.RENDER_CACHE_DIRECTORY)
	private val nativeMapTextureRepository = FlightNativeMapTextureRepository(app)
	private val assetLocks = ConcurrentHashMap<String, ReentrantLock>()
	private val decodedTerrainCache = object : LinkedHashMap<TerrainTileId, TerrariumTile>(
		MAXIMUM_DECODED_TERRAIN_TILES + 1,
		0.75f,
		true
	) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TerrainTileId, TerrariumTile>?): Boolean =
			size > MAXIMUM_DECODED_TERRAIN_TILES
	}
	private val geometryCache = object : LinkedHashMap<FlightTerrainGeometryCacheKey, FlightTerrainGeometry>(
		MAXIMUM_GEOMETRY_CACHE_TILES + 1,
		0.75f,
		true
	) {
		override fun put(
			key: FlightTerrainGeometryCacheKey,
			value: FlightTerrainGeometry
		): FlightTerrainGeometry? {
			val previous = super.put(key, value)
			var bytes = values.sumOf { geometry -> geometryBytes(geometry) }
			val iterator = entries.iterator()
			while (iterator.hasNext() &&
				(size > MAXIMUM_GEOMETRY_CACHE_TILES || bytes > MAXIMUM_GEOMETRY_CACHE_BYTES)
			) {
				val entry = iterator.next()
				bytes -= geometryBytes(entry.value)
				iterator.remove()
			}
			return previous
		}

		private fun geometryBytes(geometry: FlightTerrainGeometry): Long =
			geometry.vertices.size.toLong() * FLOAT_BYTES + geometry.indices.size.toLong() * SHORT_BYTES
	}
	private val geometryGeneration = AtomicLong(1L)
	private val geometryWorkerCount = FlightTerrainCpuScheduler.geometryWorkerCount()
	private val coordinateOriginLock = Any()
	private var coordinateOrigin: Pair<Double, Double>? = null
	private val lodLock = Any()
	private var lodQuality: FlightSatelliteQuality? = null
	private val previousTierByTile = object : LinkedHashMap<TerrainTileId, FlightTerrainTextureTier>(
		MAXIMUM_LOD_HISTORY_TILES + 1,
		0.75f,
		true
	) {
		override fun removeEldestEntry(
			eldest: MutableMap.MutableEntry<TerrainTileId, FlightTerrainTextureTier>?
		): Boolean = size > MAXIMUM_LOD_HISTORY_TILES
	}
	private val previousGeometryQuadsByTile = object : LinkedHashMap<TerrainTileId, Int>(
		MAXIMUM_LOD_HISTORY_TILES + 1,
		0.75f,
		true
	) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TerrainTileId, Int>?): Boolean =
			size > MAXIMUM_LOD_HISTORY_TILES
	}

	suspend fun loadScene(
		latitude: Double,
		longitude: Double,
		radiusKm: Int,
		satelliteQuality: FlightSatelliteQuality,
		terrainFineZoom: Int,
		terrainMiddleZoom: Int,
		detailFocus: FlightTerrainDetailFocus?,
		includeNativeMap: Boolean,
		previousScene: FlightTerrainScene? = null,
		onScene: suspend (FlightTerrainScene) -> Unit = {},
		onStatus: suspend (FlightTerrainStatus) -> Unit
	): FlightTerrainScene {
		onStatus(FlightTerrainStatus(phase = FlightTerrainPhase.PLANNING))
		val plan = runInterruptible(Dispatchers.Default) {
			FlightTerrainTilePlanner.scenePlan(latitude, longitude, radiusKm)
		}
		val origin = coordinateOriginFor(latitude, longitude)
		val orderedTiles = plan.tiles.sortedBy { tile -> tileDistanceKm(tile, latitude, longitude) }
		val orderedPlan = plan.copy(tiles = orderedTiles)
		val safeFineZoom = terrainFineZoom.coerceIn(
			FlightPlan.MIN_TERRAIN_DETAIL_ZOOM,
			FlightPlan.MAX_TERRAIN_DETAIL_ZOOM
		)
		val safeMiddleZoom = terrainMiddleZoom.coerceIn(
			FlightPlan.MIN_TERRAIN_DETAIL_ZOOM,
			safeFineZoom
		)
		val refinementFoci = buildList {
			add(FlightTerrainDetailFocus(latitude, longitude))
			if (detailFocus != null && FlightTerrainTilePlanner.distanceKm(
					latitude,
					longitude,
					detailFocus.latitude,
					detailFocus.longitude
				) >= FlightTerrainRefinementPolicy.MINIMUM_DISTINCT_FOCUS_KM
			) add(detailFocus)
		}
		val refinementLayers = refinementLayers(
			baseZoom = plan.zoom,
			foci = refinementFoci,
			fineZoom = safeFineZoom,
			middleZoom = safeMiddleZoom
		)
		val refinementQuadsByTile = refinementLayers
			.flatMap { it.quadsByTile.entries }
			.associate { it.toPair() }
		val requestedRefinementTiles = refinementLayers.flatMapTo(linkedSetOf()) { it.plan.tiles }
		val geometryQuadsByTile = selectGeometryQuads(orderedTiles, latitude, longitude, radiusKm)
		val targetTierByTile = selectTextureTiers(
			tiles = orderedTiles,
			latitude = latitude,
			longitude = longitude,
			detailFocus = detailFocus,
			radiusKm = radiusKm,
			requested = satelliteQuality
		)
		val tiles = linkedMapOf<TerrainTileId, TerrariumTile>()
		val refinementTiles = linkedMapOf<TerrainTileId, TerrariumTile>()
		val standardTexturePaths = linkedMapOf<TerrainTileId, String>()
		val detailedTexturePaths = linkedMapOf<TerrainTileId, String>()
		val satelliteTexturePaths = linkedMapOf<TerrainTileId, String>()
		val activeTextureTiers = linkedMapOf<TerrainTileId, FlightTerrainTextureTier>()
		val downloadedTileIds = linkedSetOf<TerrainTileId>()
		var failed = 0
		var refinementFailed = 0
		var satelliteFailed = 0
		var detailedFailed = 0
		val satelliteDownloadsEnabled = AtomicBoolean(true)
		var bytesDownloaded = 0L
		var memoryCacheHits = 0
		var diskCacheHits = 0
		var networkRequests = 0
		var visualUpdates = 0
		var lastScenePublishNanos = 0L
		var lastPublishedVisualUpdates = 0
		var latestScene: FlightTerrainScene? = null
		val downloadRate = DownloadRateTracker()

		fun updateActiveTexture(tileId: TerrainTileId) {
			val targetTier = targetTierByTile[tileId] ?: FlightTerrainTextureTier.OVERVIEW
			val targetQuality = FlightTerrainLodPolicy.satelliteQuality(targetTier)
			val detailPath = detailedTexturePaths[tileId]
			val standardPath = standardTexturePaths[tileId]
			when {
				targetTier == FlightTerrainTextureTier.OVERVIEW -> {
					satelliteTexturePaths.remove(tileId)
					activeTextureTiers[tileId] = FlightTerrainTextureTier.OVERVIEW
				}
				targetQuality != null && targetQuality != FlightSatelliteQuality.STANDARD && detailPath != null -> {
					satelliteTexturePaths[tileId] = detailPath
					activeTextureTiers[tileId] = targetTier
				}
				standardPath != null -> {
					satelliteTexturePaths[tileId] = standardPath
					activeTextureTiers[tileId] = FlightTerrainTextureTier.STANDARD
				}
				else -> {
					satelliteTexturePaths.remove(tileId)
					activeTextureTiers[tileId] = FlightTerrainTextureTier.OVERVIEW
				}
			}
		}

		// Reuse decoded elevations and every texture already persisted before any network work.
		// This makes quality changes and small scene shifts publish a useful frame immediately.
		orderedTiles.forEach { tileId ->
			cachedDecodedTerrain(tileId)?.let { tile ->
				tiles[tileId] = tile
				memoryCacheHits++
			}
			existingCachedFile(satelliteFile(tileId))?.let { file ->
				standardTexturePaths[tileId] = file.absolutePath
				diskCacheHits++
			}
			val targetQuality = FlightTerrainLodPolicy.satelliteQuality(targetTierByTile.getValue(tileId))
			if (targetQuality != null && targetQuality != FlightSatelliteQuality.STANDARD) {
				existingCachedFile(satelliteRenderFile(tileId, targetQuality))?.let { file ->
					detailedTexturePaths[tileId] = file.absolutePath
					diskCacheHits++
				}
			}
			updateActiveTexture(tileId)
		}
		requestedRefinementTiles.forEach { tileId ->
			cachedDecodedTerrain(tileId)?.let { tile ->
				refinementTiles[tileId] = tile
				memoryCacheHits++
			}
		}

		suspend fun <T> interruptibleResult(block: () -> T): Result<T> = try {
			Result.success(runInterruptible(Dispatchers.IO) { block() })
		} catch (error: CancellationException) {
			throw error
		} catch (error: Throwable) {
			currentCoroutineContext().ensureActive()
			Result.failure(error)
		}

		fun tierCount(tier: FlightTerrainTextureTier): Int =
			orderedTiles.count { activeTextureTiers[it] == tier }

		fun estimatedVisibleGpuBytes(): Long {
			val geometryBytes = tiles.keys.sumOf { tileId ->
				FlightTerrainGeometryLodPolicy.estimatedBytes(
					geometryQuadsByTile[tileId] ?: FlightTerrainMeshBuilder.DEFAULT_GRID_QUADS
				)
			}
			val refinementGeometryBytes = refinementTiles.keys.sumOf { tileId ->
				FlightTerrainGeometryLodPolicy.estimatedBytes(
					refinementQuadsByTile[tileId] ?: FlightTerrainRefinementPolicy.MIDDLE_GRID_QUADS
				)
			}
			val placeholderBytes = (orderedTiles.size - tiles.size).coerceAtLeast(0).toLong() *
				ESTIMATED_PLACEHOLDER_GEOMETRY_BYTES_PER_TILE
			val standardBaseBytes = orderedTiles.count { standardTexturePaths[it] != null }.toLong() *
				FlightTerrainLodPolicy.estimatedTextureBytes(FlightTerrainTextureTier.STANDARD)
			val detailBytes = orderedTiles.sumOf { tileId ->
				val tier = activeTextureTiers[tileId] ?: FlightTerrainTextureTier.OVERVIEW
				if (tier.ordinal >= FlightTerrainTextureTier.HIGH.ordinal) {
					FlightTerrainLodPolicy.estimatedTextureBytes(tier)
				} else 0L
			}
			return geometryBytes + refinementGeometryBytes + placeholderBytes + standardBaseBytes + detailBytes
		}

		fun status(
			phase: FlightTerrainPhase,
			message: String? = null,
			textureQueue: Int = 0,
			nativeMapTiles: Int = 0,
			nativeMapFailedTiles: Int = 0
		): FlightTerrainStatus = FlightTerrainStatus(
			phase = phase,
			requestedTiles = orderedTiles.size + requestedRefinementTiles.size,
			availableTiles = tiles.size + refinementTiles.size,
			downloadedTiles = downloadedTileIds.size,
			failedTiles = failed + refinementFailed,
			satelliteTiles = standardTexturePaths.size,
			satelliteFailedTiles = satelliteFailed,
			nativeMapTiles = nativeMapTiles,
			nativeMapFailedTiles = nativeMapFailedTiles,
			bytesDownloaded = bytesDownloaded,
			bytesPerSecond = downloadRate.bytesPerSecond(),
			overviewTextureTiles = tierCount(FlightTerrainTextureTier.OVERVIEW),
			standardTextureTiles = tierCount(FlightTerrainTextureTier.STANDARD),
			highTextureTiles = tierCount(FlightTerrainTextureTier.HIGH),
			ultraTextureTiles = tierCount(FlightTerrainTextureTier.ULTRA),
			ultraPlusTextureTiles = tierCount(FlightTerrainTextureTier.ULTRA_PLUS),
			ultraPlusPlusTextureTiles = tierCount(FlightTerrainTextureTier.ULTRA_PLUS_PLUS),
			ultraPlusPlusPlusTextureTiles = tierCount(FlightTerrainTextureTier.ULTRA_PLUS_PLUS_PLUS),
			memoryCacheHits = memoryCacheHits,
			diskCacheHits = diskCacheHits,
			networkRequests = networkRequests,
			decodedTerrainCacheTiles = decodedTerrainCacheSize(),
			decodedTerrainCacheBytes = decodedTerrainCacheBytes(),
			geometryCacheTiles = geometryCacheSize(),
			geometryCacheBytes = geometryCacheBytes(),
			textureQueue = textureQueue,
			estimatedVisibleGpuBytes = estimatedVisibleGpuBytes(),
			zoom = plan.zoom,
			message = message
		)

		suspend fun buildBaseScene(
			nativeMapTexturePaths: Map<TerrainTileId, String> = emptyMap(),
			nativeMapFailedTiles: Int = 0
		): FlightTerrainScene = FlightTerrainMeshBuilder.buildParallel(
				centerLatitude = latitude,
				centerLongitude = longitude,
				detailFocus = detailFocus,
				radiusKm = radiusKm,
				plan = orderedPlan,
				tiles = tiles.toMap(),
				satelliteQuality = satelliteQuality,
				terrainFineZoom = safeFineZoom,
				terrainMiddleZoom = safeMiddleZoom,
				satelliteTexturePaths = satelliteTexturePaths.toMap(),
				standardSatelliteTexturePaths = standardTexturePaths.toMap(),
				satelliteTextureTiers = activeTextureTiers.toMap(),
				nativeMapTexturePaths = nativeMapTexturePaths,
				nativeMapFailedTiles = nativeMapFailedTiles,
				nativeMapRequested = includeNativeMap,
				coordinateOriginLatitude = origin.first,
				coordinateOriginLongitude = origin.second,
				geometryQuadsByTile = geometryQuadsByTile,
				geometryCache = geometryCache,
				geometryGeneration = sceneGeometryGeneration(tiles.keys, geometryQuadsByTile),
				includePlaceholders = true,
				workerCount = geometryWorkerCount
			)

		suspend fun withRefinementMeshes(
			baseScene: FlightTerrainScene,
			retainPreviousRefinements: Boolean
		): FlightTerrainScene {
			val meshes = baseScene.meshes.filter { it.refinementLevel == 0 }.toMutableList()
			var boundaryZoom = plan.zoom
			var boundaryTiles: Map<TerrainTileId, TerrariumTile> = tiles.toMap()
			refinementLayers.forEach { layer ->
				val availableTiles = layer.plan.tiles.mapNotNull { tileId ->
					refinementTiles[tileId]?.let { tileId to it }
				}.toMap()
				if (availableTiles.isNotEmpty()) {
					val availablePlan = layer.plan.copy(tiles = layer.plan.tiles.filter(availableTiles::containsKey))
					meshes += FlightTerrainMeshBuilder.buildRefinementMeshesParallel(
						baseZoom = plan.zoom,
						plan = availablePlan,
						tiles = availableTiles,
						boundaryZoom = boundaryZoom,
						boundaryTiles = boundaryTiles,
						baseSatelliteTexturePaths = satelliteTexturePaths,
						baseStandardSatelliteTexturePaths = standardTexturePaths,
						baseSatelliteTextureTiers = activeTextureTiers,
						coordinateOriginLatitude = origin.first,
						coordinateOriginLongitude = origin.second,
						geometryQuadsByTile = layer.quadsByTile,
						geometryCache = geometryCache,
						workerCount = geometryWorkerCount
					)
					boundaryZoom = layer.plan.zoom
					boundaryTiles = availableTiles
				}
			}
			if (retainPreviousRefinements && previousScene != null &&
				previousScene.coordinateOriginLatitude == baseScene.coordinateOriginLatitude &&
				previousScene.coordinateOriginLongitude == baseScene.coordinateOriginLongitude
			) {
				val present = meshes.mapTo(hashSetOf()) { it.tileId }
				meshes += previousScene.meshes.filter { it.refinementLevel > 0 && it.tileId !in present }
			}
			val combinedQuads = geometryQuadsByTile + refinementQuadsByTile
			return baseScene.copy(
				meshes = meshes.sortedBy { it.tileId.zoom },
				loadedTiles = tiles.size + refinementTiles.size,
				missingTiles = (orderedTiles.size - tiles.size).coerceAtLeast(0) +
					(requestedRefinementTiles.size - refinementTiles.size).coerceAtLeast(0),
				satelliteTiles = meshes.count { it.satelliteTexturePath != null },
				geometryGeneration = sceneGeometryGeneration(meshes.map { it.tileId }, combinedQuads),
				generation = System.nanoTime()
			)
		}

		suspend fun publishProgressiveScene(
			force: Boolean = false,
			retainPreviousCoverage: Boolean = true
		) {
			val now = System.nanoTime()
			val enoughNewTiles = visualUpdates - lastPublishedVisualUpdates >= SCENE_PUBLISH_TILE_BATCH
			val enoughTime = now - lastScenePublishNanos >= SCENE_PUBLISH_INTERVAL_NANOS
			if (!force && latestScene != null && !enoughNewTiles && !enoughTime) return
			val built = withRefinementMeshes(
				baseScene = buildBaseScene(),
				retainPreviousRefinements = true
			)
			latestScene = if (retainPreviousCoverage && previousScene != null &&
				previousScene.coordinateOriginLatitude == built.coordinateOriginLatitude &&
				previousScene.coordinateOriginLongitude == built.coordinateOriginLongitude
			) {
				val previousByLayer = previousScene.meshes.associateBy { it.tileId to it.refinementLevel }
				val retainedMeshes = built.meshes.map { current ->
					val previous = previousByLayer[current.tileId to current.refinementLevel]
					if (!current.terrainAvailable && previous?.terrainAvailable == true) {
						// Never replace a real resident surface with the zero-metre loading plane.
						// Keep any newly available texture handles while retaining the old geometry.
						previous.copy(
							satelliteTexturePath = current.satelliteTexturePath ?: previous.satelliteTexturePath,
							standardSatelliteTexturePath = current.standardSatelliteTexturePath
								?: previous.standardSatelliteTexturePath,
							satelliteTextureTier = if (current.satelliteTexturePath != null) {
								current.satelliteTextureTier
							} else previous.satelliteTextureTier,
							nativeMapTexturePath = current.nativeMapTexturePath ?: previous.nativeMapTexturePath
						)
					} else current
				}
				val presentLayers = retainedMeshes.mapTo(hashSetOf()) { it.tileId to it.refinementLevel }
				built.copy(
					meshes = retainedMeshes + previousScene.meshes.filter {
						(it.tileId to it.refinementLevel) !in presentLayers
					}
				)
			} else built
			lastScenePublishNanos = now
			lastPublishedVisualUpdates = visualUpdates
			onScene(latestScene ?: return)
		}

		onStatus(status(FlightTerrainPhase.DOWNLOADING, "Cache local et relief…"))
		publishProgressiveScene(force = true, retainPreviousCoverage = true)

		// One queue owns both coarse coverage and detailed elevation. Distance is the
		// primary key; inside each 5 km band, cheap base coverage is completed before
		// expensive refinement. A cancelled scene discards this entire queue, and the
		// replacement scene rebuilds it around the latest aircraft position.
		val terrainRequests = buildList {
			orderedTiles.filterNot(tiles::containsKey).forEach { tileId ->
				val quads = geometryQuadsByTile[tileId] ?: FlightTerrainMeshBuilder.DEFAULT_GRID_QUADS
				add(
					TerrainElevationWork(
						tileId = tileId,
						refinement = false,
						distanceKm = tileNearestDistanceKm(tileId, latitude, longitude),
						complexity = estimatedGeometryComplexity(quads)
					)
				)
			}
			requestedRefinementTiles.filterNot(refinementTiles::containsKey).forEach { tileId ->
				val quads = refinementQuadsByTile[tileId] ?: FlightTerrainRefinementPolicy.MIDDLE_GRID_QUADS
				add(
					TerrainElevationWork(
						tileId = tileId,
						refinement = true,
						distanceKm = tileNearestDistanceKm(tileId, latitude, longitude),
						complexity = estimatedGeometryComplexity(quads)
					)
				)
			}
		}.sortedWith(
			compareBy<TerrainElevationWork>(
				{ (it.distanceKm / PRIORITY_DISTANCE_BUCKET_KM).toInt() },
				{ if (it.refinement) 1 else 0 },
				{ it.complexity },
				{ it.distanceKm },
				{ it.tileId.zoom },
				{ it.tileId.y },
				{ it.tileId.x }
			)
		)
		if (terrainRequests.isNotEmpty()) coroutineScope {
			val work = Channel<TerrainElevationWork>(PARALLEL_DOWNLOADS * 2)
			val results = Channel<TerrainElevationResult>(PARALLEL_DOWNLOADS * 2)
			val producer = launch {
				terrainRequests.forEach { request -> work.send(request) }
				work.close()
			}
			val workers = List(PARALLEL_DOWNLOADS) {
				launch {
					for (request in work) {
						results.send(
							TerrainElevationResult(
								request,
								interruptibleResult { loadTerrainTile(request.tileId) }
							)
						)
					}
				}
			}
			repeat(terrainRequests.size) { index ->
				val completed = results.receive()
				completed.result.onSuccess { loaded ->
					if (completed.work.refinement) {
						refinementTiles[loaded.tile.id] = loaded.tile
					} else {
						tiles[loaded.tile.id] = loaded.tile
					}
					invalidateGeometryAround(loaded.tile.id)
					memoryCacheHits += loaded.memoryCacheHits
					diskCacheHits += loaded.diskCacheHits
					networkRequests += loaded.networkRequests
					if (loaded.networkRequests > 0) downloadedTileIds += loaded.tile.id
					bytesDownloaded += loaded.downloadedBytes
					downloadRate.record(loaded.downloadedBytes)
					visualUpdates++
				}.onFailure {
					if (completed.work.refinement) refinementFailed++ else failed++
				}
				onStatus(
					status(
						FlightTerrainPhase.DOWNLOADING,
						message = "Relief prioritaire ${tiles.size + refinementTiles.size}/" +
							"${orderedTiles.size + requestedRefinementTiles.size} · proche · grossier avant fin",
						textureQueue = orderedTiles.size - standardTexturePaths.size
					)
				)
				publishProgressiveScene(
					force = index == terrainRequests.lastIndex,
					retainPreviousCoverage = true
				)
			}
			producer.join()
			workers.forEach { it.join() }
			results.close()
		}
		if (tiles.isEmpty()) throw IOException("Aucune tuile de relief disponible")
		if (terrainRequests.isEmpty()) {
			publishProgressiveScene(force = true, retainPreviousCoverage = true)
		}

		// Standard and detailed imagery use separate worker lanes but a single result
		// queue. Four cheap Standard workers keep distant coverage moving while two
		// expensive detail workers refine the nearest tiles; all state publication stays
		// serialized here. This avoids making the aircraft wait for the full 300 km base.
		val standardRequests = orderedTiles
			.filter { it !in standardTexturePaths }
			.map { tileId ->
				SatelliteTextureWork(
					tileId = tileId,
					quality = FlightSatelliteQuality.STANDARD,
					distanceKm = tileNearestDistanceKm(tileId, latitude, longitude),
					complexity = 1L
				)
			}
			.sortedWith(SATELLITE_WORK_COMPARATOR)
		val detailRequests = orderedTiles.mapNotNull { tileId ->
			val tier = targetTierByTile[tileId] ?: return@mapNotNull null
			val quality = FlightTerrainLodPolicy.satelliteQuality(tier) ?: return@mapNotNull null
			if (quality == FlightSatelliteQuality.STANDARD || tileId !in tiles || tileId in detailedTexturePaths) {
				null
			} else {
				SatelliteTextureWork(
					tileId = tileId,
					quality = quality,
					distanceKm = tileNearestDistanceKm(tileId, latitude, longitude),
					complexity = estimatedTextureComplexity(quality)
				)
			}
		}.sortedWith(SATELLITE_WORK_COMPARATOR)
		val totalTextureRequests = standardRequests.size + detailRequests.size
		if (totalTextureRequests > 0) coroutineScope {
			val standardWork = Channel<SatelliteTextureWork>(SATELLITE_STANDARD_PARALLEL_DOWNLOADS * 2)
			val detailWork = Channel<SatelliteTextureWork>(DETAIL_PARALLEL_DOWNLOADS * 2)
			val results = Channel<SatelliteTextureResult>(
				(SATELLITE_STANDARD_PARALLEL_DOWNLOADS + DETAIL_PARALLEL_DOWNLOADS) * 2
			)
			val standardProducer = launch {
				standardRequests.forEach { request -> standardWork.send(request) }
				standardWork.close()
			}
			val detailProducer = launch {
				detailRequests.forEach { request -> detailWork.send(request) }
				detailWork.close()
			}
			val standardWorkers = List(SATELLITE_STANDARD_PARALLEL_DOWNLOADS) {
				launch {
					for (request in standardWork) {
						results.send(
							SatelliteTextureResult(
								request,
								interruptibleResult {
									loadStandardSatelliteTexture(
										request.tileId,
										satelliteDownloadsEnabled.get()
									)
								}
							)
						)
					}
				}
			}
			val detailWorkers = List(DETAIL_PARALLEL_DOWNLOADS) {
				launch {
					for (request in detailWork) {
						results.send(
							SatelliteTextureResult(
								request,
								interruptibleResult {
									loadDetailedSatelliteTexture(
										request.tileId,
										request.quality,
										satelliteDownloadsEnabled.get()
									)
								}
							)
						)
					}
				}
			}
			var standardCompleted = 0
			var detailCompleted = 0
			repeat(totalTextureRequests) { index ->
				val completed = results.receive()
				val isStandard = completed.work.quality == FlightSatelliteQuality.STANDARD
				if (isStandard) standardCompleted++ else detailCompleted++
				completed.result.onSuccess { loaded ->
					if (isStandard) {
						standardTexturePaths[loaded.tileId] = loaded.file.absolutePath
					} else {
						detailedTexturePaths[loaded.tileId] = loaded.file.absolutePath
					}
					updateActiveTexture(loaded.tileId)
					diskCacheHits += loaded.diskCacheHits
					networkRequests += loaded.networkRequests
					if (loaded.networkRequests > 0) downloadedTileIds += loaded.tileId
					bytesDownloaded += loaded.downloadedBytes
					downloadRate.record(loaded.downloadedBytes)
					visualUpdates++
				}.onFailure {
					if (isStandard) satelliteFailed++ else detailedFailed++
				}
				if (standardTexturePaths.isEmpty() && satelliteFailed >= SATELLITE_FAILURE_CIRCUIT_BREAKER) {
					satelliteDownloadsEnabled.set(false)
				}
				onStatus(
					status(
						phase = FlightTerrainPhase.DOWNLOADING,
						message = "Textures · Standard $standardCompleted/${standardRequests.size}" +
							" · détail $detailCompleted/${detailRequests.size}" +
							if (detailedFailed > 0) " · $detailedFailed en repli" else "",
						textureQueue = totalTextureRequests - index - 1
					)
				)
				publishProgressiveScene(force = index == totalTextureRequests - 1)
			}
			standardProducer.join()
			detailProducer.join()
			standardWorkers.forEach { it.join() }
			detailWorkers.forEach { it.join() }
			results.close()
		}

		val buildingStatus = status(
			phase = FlightTerrainPhase.BUILDING,
			message = when {
				includeNativeMap -> "Rendu de la carte OsmAnd sur le relief…"
				detailedFailed > 0 -> "Maillage GPU · $detailedFailed texture(s) détaillée(s) gardée(s) en Standard"
				else -> "Finalisation de la scène…"
			}
		)
		onStatus(buildingStatus)
		val nativeMapResult = if (includeNativeMap) {
			nativeMapTextureRepository.renderTextures(tiles.keys) { completed, mapAvailable, mapFailed ->
				onStatus(
					buildingStatus.copy(
						nativeMapTiles = mapAvailable,
						nativeMapFailedTiles = mapFailed,
						message = "Carte OsmAnd sur relief : $completed/${tiles.size} tuiles"
					)
				)
			}
		} else {
			FlightNativeMapTextureResult(emptyMap(), 0)
		}
		val finalBaseScene = buildBaseScene(
			nativeMapTexturePaths = nativeMapResult.texturePaths,
			nativeMapFailedTiles = nativeMapResult.failedTiles
		)
		val finalScene = withRefinementMeshes(finalBaseScene, retainPreviousRefinements = false)
		onScene(finalScene)
		return finalScene
	}

	private fun refinementLayers(
		baseZoom: Int,
		foci: List<FlightTerrainDetailFocus>,
		fineZoom: Int,
		middleZoom: Int
	): List<TerrainRefinementLayer> {
		val initialMiddlePlan = middleZoom.takeIf { it > baseZoom }?.let { zoom ->
			FlightTerrainTilePlanner.refinementPlan(
				foci = foci,
				radiusKm = FlightTerrainRefinementPolicy.MIDDLE_RADIUS_KM,
				zoom = zoom,
				maxTiles = FlightTerrainRefinementPolicy.MAXIMUM_MIDDLE_TILES
			)
		}
		val finePlan = fineZoom.takeIf { it > baseZoom }?.let { zoom ->
			FlightTerrainTilePlanner.refinementPlan(
				foci = foci,
				radiusKm = FlightTerrainRefinementPolicy.FINE_RADIUS_KM,
				zoom = zoom,
				maxTiles = FlightTerrainRefinementPolicy.MAXIMUM_FINE_TILES
			)
		}
		val middlePlan = if (initialMiddlePlan != null && finePlan != null &&
			initialMiddlePlan.zoom < finePlan.zoom
		) {
			val factor = 1 shl (finePlan.zoom - initialMiddlePlan.zoom)
			val fineParents = finePlan.tiles.map { tile ->
				TerrainTileId(initialMiddlePlan.zoom, tile.x / factor, tile.y / factor)
			}.distinct()
			initialMiddlePlan.copy(
				tiles = (fineParents + initialMiddlePlan.tiles).distinct()
					.take(FlightTerrainRefinementPolicy.MAXIMUM_MIDDLE_TILES)
			)
		} else initialMiddlePlan
		if (middlePlan == null && finePlan == null) return emptyList()
		if (middlePlan != null && finePlan != null && middlePlan.zoom == finePlan.zoom) {
			val fineIds = finePlan.tiles.toHashSet()
			val tiles = (finePlan.tiles + middlePlan.tiles).distinct()
			return listOf(
				TerrainRefinementLayer(
					plan = TerrainTilePlan(middlePlan.zoom, tiles),
					quadsByTile = tiles.associateWith { tileId ->
						if (tileId in fineIds) {
							FlightTerrainRefinementPolicy.FINE_GRID_QUADS
						} else FlightTerrainRefinementPolicy.MIDDLE_GRID_QUADS
					}
				)
			)
		}
		return buildList {
			middlePlan?.let { plan ->
				add(
					TerrainRefinementLayer(
						plan,
						plan.tiles.associateWith { FlightTerrainRefinementPolicy.MIDDLE_GRID_QUADS }
					)
				)
			}
			finePlan?.let { plan ->
				add(
					TerrainRefinementLayer(
						plan,
						plan.tiles.associateWith { FlightTerrainRefinementPolicy.FINE_GRID_QUADS }
					)
				)
			}
		}
	}

	suspend fun preloadCorridor(
		plan: FlightPlan,
		trip: FlightTrip?,
		onStatus: suspend (FlightTerrainStatus) -> Unit
	): FlightTerrainStatus {
		onStatus(FlightTerrainStatus(phase = FlightTerrainPhase.PLANNING))
		val tilePlan = withContext(Dispatchers.Default) {
			trip?.samples?.takeIf { it.size >= 2 }?.let {
				FlightTerrainTilePlanner.trackCorridorPlan(it, plan.terrainCorridorKm)
			} ?: FlightTerrainTilePlanner.corridorPlan(plan.stops, plan.terrainCorridorKm)
		} ?: throw IllegalArgumentException("Il faut une trace ou les coordonnées d’au moins deux villes")
		var available = 0
		var downloaded = 0
		var failed = 0
		var satelliteAvailable = 0
		var satelliteFailed = 0
		var satelliteDownloadsEnabled = true
		var bytesDownloaded = 0L
		for (chunk in tilePlan.tiles.chunked(PARALLEL_DOWNLOADS)) {
			val allowSatelliteDownload = satelliteDownloadsEnabled
			val results = coroutineScope {
				chunk.map { tileId ->
					async(Dispatchers.IO) {
						runCatching { ensureOfflineTileFiles(tileId, allowSatelliteDownload) }
					}
				}.awaitAll()
			}
			results.forEach { result ->
				result.onSuccess { cached ->
					available++
					if (cached.downloaded) downloaded++
					if (cached.standardSatellite != null) satelliteAvailable++
					else if (cached.satelliteAttempted) satelliteFailed++
					bytesDownloaded += cached.downloadedBytes
				}.onFailure {
					failed++
				}
			}
			if (satelliteAvailable == 0 && satelliteFailed >= SATELLITE_FAILURE_CIRCUIT_BREAKER) {
				satelliteDownloadsEnabled = false
			}
			onStatus(
				FlightTerrainStatus(
					phase = FlightTerrainPhase.DOWNLOADING,
					requestedTiles = tilePlan.tiles.size,
					availableTiles = available,
					downloadedTiles = downloaded,
					failedTiles = failed,
					satelliteTiles = satelliteAvailable,
					satelliteFailedTiles = satelliteFailed,
					bytesDownloaded = bytesDownloaded,
					zoom = tilePlan.zoom
				)
			)
		}
		if (available == 0) throw IOException("Aucune tuile de relief n’a pu être préchargée")
		return FlightTerrainStatus(
			phase = FlightTerrainPhase.READY,
			requestedTiles = tilePlan.tiles.size,
			availableTiles = available,
			downloadedTiles = downloaded,
			failedTiles = failed,
			satelliteTiles = satelliteAvailable,
			satelliteFailedTiles = satelliteFailed,
			bytesDownloaded = bytesDownloaded,
			zoom = tilePlan.zoom,
			message = when {
				failed > 0 -> "$failed tuiles de relief indisponibles"
				satelliteFailed > 0 -> "Relief prêt · satellite Standard partiel ($satelliteFailed manquantes)"
				else -> "Relief + satellite Standard rattachés au trajet"
			}
		)
	}

	private fun loadTerrainTile(tileId: TerrainTileId): LoadedTerrainTile {
		ensureWorkActive()
		cachedDecodedTerrain(tileId)?.let { tile ->
			return LoadedTerrainTile(
				tile = tile,
				downloadedBytes = 0L,
				memoryCacheHits = 1,
				diskCacheHits = 0,
				networkRequests = 0
			)
		}
		var cached = ensureTerrainFile(tileId)
		ensureWorkActive()
		var tile = decodeTile(tileId, cached.file)
		if (tile == null && !cached.downloaded) {
			ensureWorkActive()
			cached.file.delete()
			cached = downloadTerrainTile(tileId, cached.file)
			ensureWorkActive()
			tile = decodeTile(tileId, cached.file)
		}
		return LoadedTerrainTile(
			tile = tile ?: throw IOException("Tuile Terrarium illisible: ${tileId.zoom}/${tileId.x}/${tileId.y}"),
			downloadedBytes = cached.downloadedBytes,
			memoryCacheHits = 0,
			diskCacheHits = cached.diskCacheHits,
			networkRequests = cached.networkRequests
		)
	}

	private fun loadStandardSatelliteTexture(
		tileId: TerrainTileId,
		allowSatelliteDownload: Boolean
	): LoadedSatelliteTexture {
		val cached = ensureSatelliteSourceFile(tileId, allowSatelliteDownload)
		return LoadedSatelliteTexture(
			tileId = tileId,
			file = cached.file,
			downloadedBytes = cached.downloadedBytes,
			diskCacheHits = cached.diskCacheHits,
			networkRequests = cached.networkRequests
		)
	}

	private fun loadDetailedSatelliteTexture(
		tileId: TerrainTileId,
		quality: FlightSatelliteQuality,
		allowSatelliteDownload: Boolean
	): LoadedSatelliteTexture {
		require(quality != FlightSatelliteQuality.STANDARD)
		val cached = ensureSatelliteTexture(tileId, quality, allowSatelliteDownload)
		return LoadedSatelliteTexture(
			tileId = tileId,
			file = cached.file,
			downloadedBytes = cached.downloadedBytes,
			diskCacheHits = cached.diskCacheHits,
			networkRequests = cached.networkRequests
		)
	}

	private fun ensureOfflineTileFiles(
		tileId: TerrainTileId,
		allowSatelliteDownload: Boolean
	): CachedTileFiles {
		val terrain = ensureTerrainFile(tileId)
		// Offline journals always own the immutable parent Standard tile. Detailed composites are
		// transient render derivatives around the aircraft and would make a 300 km corridor huge.
		val standardSatellite = runCatching {
			ensureSatelliteSourceFile(tileId, allowSatelliteDownload)
		}.getOrNull()
		return CachedTileFiles(
			terrain = terrain,
			standardSatellite = standardSatellite,
			satelliteAttempted = allowSatelliteDownload
		)
	}

	private fun ensureTerrainFile(tileId: TerrainTileId): CachedAsset {
		val file = tileFile(tileId)
		return withAssetLock(file) {
			if (file.isFile && file.length() > 0L) {
				CachedAsset(file, downloaded = false, downloadedBytes = 0L, diskCacheHits = 1, networkRequests = 0)
			} else {
				downloadTerrainTile(tileId, file)
			}
		}
	}

	private fun ensureSatelliteTexture(
		tileId: TerrainTileId,
		quality: FlightSatelliteQuality,
		allowDownload: Boolean
	): CachedAsset {
		ensureWorkActive()
		if (quality == FlightSatelliteQuality.STANDARD) {
			return ensureSatelliteSourceFile(tileId, allowDownload)
		}
		val destination = satelliteRenderFile(tileId, quality)
		return withAssetLock(destination) {
			ensureWorkActive()
			if (destination.isFile && destination.length() > 0L && isDecodableImage(destination)) {
				return@withAssetLock CachedAsset(
					destination,
					downloaded = false,
					downloadedBytes = 0L,
					diskCacheHits = 1,
					networkRequests = 0
				)
			}
			if (destination.exists() && !destination.delete()) {
				throw IOException("Texture satellite calculée verrouillée")
			}

			val factor = 1 shl quality.zoomDelta
			val childTiles = buildList {
				for (childY in 0 until factor) {
					for (childX in 0 until factor) {
						add(
							TerrainTileId(
								zoom = tileId.zoom + quality.zoomDelta,
								x = tileId.x * factor + childX,
								y = tileId.y * factor + childY
							)
						)
					}
				}
			}
			val cachedChildren = childTiles.map { childTile ->
				ensureWorkActive()
				ensureSatelliteSourceFile(childTile, allowDownload)
			}
			ensureWorkActive()
			val downloadedBytes = cachedChildren.sumOf { it.downloadedBytes }
			val firstBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			BitmapFactory.decodeFile(cachedChildren.first().file.absolutePath, firstBounds)
			val tileWidth = firstBounds.outWidth
			val tileHeight = firstBounds.outHeight
			if (tileWidth <= 0 || tileHeight <= 0) throw IOException("Texture satellite source illisible")
			val composed = Bitmap.createBitmap(tileWidth * factor, tileHeight * factor, Bitmap.Config.RGB_565)
			try {
				val canvas = Canvas(composed)
				val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
				cachedChildren.forEachIndexed { index, cached ->
					ensureWorkActive()
					val bitmap = BitmapFactory.decodeFile(
						cached.file.absolutePath,
						BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
					) ?: throw IOException("Texture satellite source illisible")
					try {
						if (bitmap.width != tileWidth || bitmap.height != tileHeight) {
							throw IOException("Textures satellite de tailles incompatibles")
						}
						val childX = index % factor
						val childY = index / factor
						canvas.drawBitmap(
							bitmap,
							null,
							Rect(
								childX * tileWidth,
								childY * tileHeight,
								(childX + 1) * tileWidth,
								(childY + 1) * tileHeight
							),
							paint
						)
					} finally {
						bitmap.recycle()
					}
				}
				val parent = destination.parentFile ?: throw IOException("Dossier satellite invalide")
				if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le cache satellite")
				val partial = File(parent, destination.name + PARTIAL_SUFFIX)
				if (partial.exists() && !partial.delete()) throw IOException("Texture satellite temporaire verrouillée")
				try {
					FileOutputStream(partial).buffered().use { output ->
						ensureWorkActive()
						if (!composed.compress(Bitmap.CompressFormat.JPEG, COMPOSITE_JPEG_QUALITY, output)) {
							throw IOException("Impossible de composer la texture satellite")
						}
					}
					ensureWorkActive()
					if (!partial.renameTo(destination)) throw IOException("Impossible de finaliser la texture satellite")
				} finally {
					if (partial.exists()) partial.delete()
				}
			} finally {
				composed.recycle()
			}
			CachedAsset(
				file = destination,
				downloaded = cachedChildren.any { it.downloaded },
				downloadedBytes = downloadedBytes,
				diskCacheHits = cachedChildren.sumOf { it.diskCacheHits },
				networkRequests = cachedChildren.sumOf { it.networkRequests }
			)
		}
	}

	private fun ensureSatelliteSourceFile(tileId: TerrainTileId, allowDownload: Boolean): CachedAsset {
		ensureWorkActive()
		val file = satelliteFile(tileId)
		return withAssetLock(file) {
			ensureWorkActive()
			if (file.isFile && file.length() > 0L && isDecodableImage(file)) {
				return@withAssetLock CachedAsset(
					file,
					downloaded = false,
					downloadedBytes = 0L,
					diskCacheHits = 1,
					networkRequests = 0
				)
			}
			if (!allowDownload) throw IOException("Téléchargement satellite temporairement désactivé")
			if (file.exists() && !file.delete()) throw IOException("Texture satellite en cache verrouillée")
			val downloaded = downloadAsset(
				url = FlightSatelliteSource.tileUrl(tileId),
				destination = file,
				accept = "image/jpeg",
				sourceName = "EOX Sentinel-2",
				connectTimeoutMillis = SATELLITE_CONNECT_TIMEOUT_MILLIS,
				readTimeoutMillis = SATELLITE_READ_TIMEOUT_MILLIS
			)
			if (!isDecodableImage(file)) {
				file.delete()
				throw IOException("Texture satellite illisible: ${tileId.zoom}/${tileId.x}/${tileId.y}")
			}
			downloaded
		}
	}

	private fun assetLock(file: File): ReentrantLock {
		val key = file.absolutePath
		val candidate = ReentrantLock()
		return assetLocks.putIfAbsent(key, candidate) ?: candidate
	}

	private inline fun <T> withAssetLock(file: File, block: () -> T): T {
		val lock = assetLock(file)
		lock.lockInterruptibly()
		return try {
			ensureWorkActive()
			block()
		} finally {
			lock.unlock()
		}
	}

	private fun downloadTerrainTile(tileId: TerrainTileId, destination: File): CachedAsset =
		downloadAsset(
			url = "$TERRARIUM_BASE_URL/${tileId.zoom}/${tileId.x}/${tileId.y}.png",
			destination = destination,
			accept = "image/png",
			sourceName = "Terrain Tiles"
		)

	private fun downloadAsset(
		url: String,
		destination: File,
		accept: String,
		sourceName: String,
		connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
		readTimeoutMillis: Int = READ_TIMEOUT_MILLIS
	): CachedAsset {
		ensureWorkActive()
		val parent = destination.parentFile ?: throw IOException("Dossier de cache invalide")
		if (!parent.exists() && !parent.mkdirs()) throw IOException("Impossible de créer le cache du relief")
		val partial = File(parent, destination.name + PARTIAL_SUFFIX)
		if (partial.exists() && !partial.delete()) throw IOException("Téléchargement temporaire verrouillé")
		val connection = NetworkUtils.getHttpURLConnection(url)
		try {
			connection.requestMethod = "GET"
			connection.connectTimeout = connectTimeoutMillis
			connection.readTimeout = readTimeoutMillis
			connection.setRequestProperty("User-Agent", Version.getFullVersion(app))
			connection.setRequestProperty("Accept", accept)
			connection.connect()
			ensureWorkActive()
			if (connection.responseCode != HttpURLConnection.HTTP_OK) {
				throw IOException("$sourceName HTTP ${connection.responseCode}")
			}
			var total = 0L
			BufferedInputStream(connection.inputStream).use { input ->
				BufferedOutputStream(FileOutputStream(partial)).use { output ->
					val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
					while (true) {
						ensureWorkActive()
						val count = input.read(buffer)
						if (count < 0) break
						total += count
						if (total > MAX_TILE_BYTES) throw IOException("Réponse $sourceName anormalement volumineuse")
						output.write(buffer, 0, count)
					}
				}
			}
			if (total == 0L) throw IOException("Réponse $sourceName vide")
			if (!partial.renameTo(destination)) {
				throw IOException("Impossible de finaliser la tuile $sourceName")
			}
			return CachedAsset(
				file = destination,
				downloaded = true,
				downloadedBytes = total,
				diskCacheHits = 0,
				networkRequests = 1
			)
		} finally {
			connection.disconnect()
			if (partial.exists()) partial.delete()
		}
	}

	private fun isDecodableImage(file: File): Boolean {
		ensureWorkActive()
		val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
		BitmapFactory.decodeFile(file.absolutePath, options)
		return options.outWidth > 0 && options.outHeight > 0
	}

	private fun decodeTile(tileId: TerrainTileId, file: File): TerrariumTile? {
		ensureWorkActive()
		synchronized(decodedTerrainCache) { decodedTerrainCache[tileId] }?.let { return it }
		val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
		val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
		try {
			val pixels = IntArray(bitmap.width * bitmap.height)
			bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
			val elevations = FloatArray(pixels.size)
			for (index in pixels.indices) {
				if (index % CANCELLATION_CHECK_PIXEL_INTERVAL == 0) ensureWorkActive()
				elevations[index] = TerrariumCodec.decodeArgb(pixels[index])
			}
			return TerrariumTile(tileId, bitmap.width, bitmap.height, elevations).also { tile ->
				synchronized(decodedTerrainCache) { decodedTerrainCache[tileId] = tile }
			}
		} finally {
			bitmap.recycle()
		}
	}

	private fun cachedDecodedTerrain(tileId: TerrainTileId): TerrariumTile? =
		synchronized(decodedTerrainCache) { decodedTerrainCache[tileId] }

	private fun ensureWorkActive() {
		if (Thread.currentThread().isInterrupted) {
			throw InterruptedIOException("Calcul de tuile obsolète annulé")
		}
	}

	private fun estimatedGeometryComplexity(gridQuads: Int): Long {
		val safeQuads = gridQuads.coerceIn(
			FlightTerrainMeshBuilder.DEFAULT_GRID_QUADS,
			FlightTerrainMeshBuilder.MAXIMUM_GRID_QUADS
		).toLong()
		return (safeQuads + 1L) * (safeQuads + 1L) + safeQuads * safeQuads * 2L
	}

	private fun estimatedTextureComplexity(quality: FlightSatelliteQuality): Long =
		1L shl (quality.zoomDelta * 2)

	private fun decodedTerrainCacheSize(): Int =
		synchronized(decodedTerrainCache) { decodedTerrainCache.size }

	private fun decodedTerrainCacheBytes(): Long = synchronized(decodedTerrainCache) {
		decodedTerrainCache.values.sumOf { it.elevationsMeters.size.toLong() * FLOAT_BYTES }
	}

	private fun geometryCacheSize(): Int = synchronized(geometryCache) { geometryCache.size }

	private fun geometryCacheBytes(): Long = synchronized(geometryCache) {
		geometryCache.values.sumOf { geometry ->
			geometry.vertices.size.toLong() * FLOAT_BYTES +
				geometry.indices.size.toLong() * SHORT_BYTES
		}
	}

	private fun sceneGeometryGeneration(
		tileIds: Collection<TerrainTileId>,
		geometryQuadsByTile: Map<TerrainTileId, Int>
	): Long {
		var result = geometryGeneration.get()
		tileIds.sortedWith(compareBy<TerrainTileId>({ it.zoom }, { it.y }, { it.x })).forEach { tile ->
			result = result * 31L + tile.zoom
			result = result * 31L + tile.x
			result = result * 31L + tile.y
			result = result * 31L + (geometryQuadsByTile[tile] ?: FlightTerrainMeshBuilder.DEFAULT_GRID_QUADS)
		}
		return result
	}

	private fun existingCachedFile(file: File): File? = file.takeIf { it.isFile && it.length() > 0L }

	private fun tileDistanceKm(tileId: TerrainTileId, latitude: Double, longitude: Double): Double =
		FlightTerrainTilePlanner.distanceKm(
			latitude,
			longitude,
			FlightTerrainTilePlanner.tileYToLatitude(tileId.y + 0.5, tileId.zoom),
			FlightTerrainTilePlanner.tileXToLongitude(tileId.x + 0.5, tileId.zoom)
		)

	private fun selectTextureTiers(
		tiles: List<TerrainTileId>,
		latitude: Double,
		longitude: Double,
		detailFocus: FlightTerrainDetailFocus?,
		radiusKm: Int,
		requested: FlightSatelliteQuality
	): Map<TerrainTileId, FlightTerrainTextureTier> = synchronized(lodLock) {
		if (lodQuality != requested) {
			previousTierByTile.clear()
			lodQuality = requested
		}
		tiles.associateWith { tile ->
			val aircraftDistance = tileNearestDistanceKm(tile, latitude, longitude)
			val focusDistance = detailFocus?.let { focus ->
				tileNearestDistanceKm(tile, focus.latitude, focus.longitude)
			}
			fun tierAt(distanceFactor: Double): FlightTerrainTextureTier {
				return FlightTerrainLodPolicy.tierForFoci(
					requested = requested,
					radiusKm = radiusKm,
					aircraftDistanceKm = aircraftDistance * distanceFactor,
					detailFocusDistanceKm = focusDistance?.times(distanceFactor)
				)
			}
			val raw = tierAt(1.0)
			val previous = previousTierByTile[tile]
			val stable = when {
				previous == null || previous == raw -> raw
				FlightTerrainLodPolicy.shouldRetainNearbyDetail(previous, raw, aircraftDistance) -> previous
				raw.ordinal < previous.ordinal -> tierAt(1.0 / LOD_HYSTERESIS_FACTOR)
				else -> tierAt(LOD_HYSTERESIS_FACTOR)
			}
			previousTierByTile[tile] = stable
			stable
		}
	}

	private fun selectGeometryQuads(
		tiles: List<TerrainTileId>,
		latitude: Double,
		longitude: Double,
		radiusKm: Int
	): Map<TerrainTileId, Int> = synchronized(lodLock) {
		tiles.associateWith { tile ->
			val nearestDistanceKm = tileNearestDistanceKm(tile, latitude, longitude)
			val stable = FlightTerrainGeometryLodPolicy.stableQuadsForDistance(
				radiusKm,
				nearestDistanceKm,
				previousGeometryQuadsByTile[tile]
			)
			previousGeometryQuadsByTile[tile] = stable
			stable
		}
	}

	private fun tileNearestDistanceKm(
		tileId: TerrainTileId,
		latitude: Double,
		longitude: Double
	): Double {
		val north = FlightTerrainTilePlanner.tileYToLatitude(tileId.y.toDouble(), tileId.zoom)
		val south = FlightTerrainTilePlanner.tileYToLatitude(tileId.y + 1.0, tileId.zoom)
		val west = FlightTerrainTilePlanner.tileXToLongitude(tileId.x.toDouble(), tileId.zoom)
		val east = FlightTerrainTilePlanner.tileXToLongitude(tileId.x + 1.0, tileId.zoom)
		val tileCenterLongitude = (west + east) * 0.5
		var unwrappedLongitude = longitude
		while (unwrappedLongitude - tileCenterLongitude > 180.0) unwrappedLongitude -= 360.0
		while (unwrappedLongitude - tileCenterLongitude < -180.0) unwrappedLongitude += 360.0
		val closestLatitude = latitude.coerceIn(south, north)
		val closestLongitude = unwrappedLongitude.coerceIn(west, east)
		return FlightTerrainTilePlanner.distanceKm(
			latitude,
			unwrappedLongitude,
			closestLatitude,
			closestLongitude
		)
	}

	private fun coordinateOriginFor(latitude: Double, longitude: Double): Pair<Double, Double> =
		synchronized(coordinateOriginLock) {
			val current = coordinateOrigin
			if (current == null || FlightTerrainTilePlanner.distanceKm(
					current.first,
					current.second,
					latitude,
					longitude
				) > COORDINATE_ORIGIN_RESET_DISTANCE_KM
			) {
				coordinateOrigin = latitude to longitude
				synchronized(geometryCache) { geometryCache.clear() }
				geometryGeneration.incrementAndGet()
			}
			coordinateOrigin ?: (latitude to longitude)
		}

	private fun invalidateGeometryAround(tileId: TerrainTileId) {
		val tileCount = 1 shl tileId.zoom
		val affectedTiles = hashSetOf<TerrainTileId>()
		for (dy in -1..1) {
			val y = tileId.y + dy
			if (y !in 0 until tileCount) continue
			for (dx in -1..1) {
				val rawX = tileId.x + dx
				val x = ((rawX % tileCount) + tileCount) % tileCount
				affectedTiles += TerrainTileId(tileId.zoom, x, y)
			}
		}
		synchronized(geometryCache) {
			geometryCache.keys.removeAll { key ->
				key.tileId in affectedTiles || isRefinementDependingOn(key, tileId.zoom, affectedTiles)
			}
		}
		geometryGeneration.incrementAndGet()
	}

	private fun isRefinementDependingOn(
		key: FlightTerrainGeometryCacheKey,
		boundaryZoom: Int,
		affectedBoundaryTiles: Set<TerrainTileId>
	): Boolean {
		if (key.boundarySourceZoom != boundaryZoom || key.tileId.zoom <= boundaryZoom) return false
		val factor = 1 shl (key.tileId.zoom - boundaryZoom)
		val parent = TerrainTileId(boundaryZoom, key.tileId.x / factor, key.tileId.y / factor)
		return parent in affectedBoundaryTiles
	}

	private fun tileFile(tileId: TerrainTileId): File =
		File(File(File(terrainDirectory, tileId.zoom.toString()), tileId.x.toString()), "${tileId.y}.png")

	private fun satelliteFile(tileId: TerrainTileId): File =
		File(File(File(satelliteDirectory, tileId.zoom.toString()), tileId.x.toString()), "${tileId.y}.jpg")

	private fun satelliteRenderFile(tileId: TerrainTileId, quality: FlightSatelliteQuality): File =
		File(
			File(
				File(File(satelliteRenderDirectory, quality.name.lowercase()), tileId.zoom.toString()),
				tileId.x.toString()
			),
			"${tileId.y}.jpg"
		)

	private data class CachedAsset(
		val file: File,
		val downloaded: Boolean,
		val downloadedBytes: Long,
		val diskCacheHits: Int,
		val networkRequests: Int
	)

	private data class CachedTileFiles(
		val terrain: CachedAsset,
		val standardSatellite: CachedAsset?,
		val satelliteAttempted: Boolean
	) {
		val downloaded: Boolean
			get() = terrain.downloaded || standardSatellite?.downloaded == true

		val downloadedBytes: Long
			get() = terrain.downloadedBytes + (standardSatellite?.downloadedBytes ?: 0L)
	}

	private data class LoadedTerrainTile(
		val tile: TerrariumTile,
		val downloadedBytes: Long,
		val memoryCacheHits: Int,
		val diskCacheHits: Int,
		val networkRequests: Int
	)

	private data class LoadedSatelliteTexture(
		val tileId: TerrainTileId,
		val file: File,
		val downloadedBytes: Long,
		val diskCacheHits: Int,
		val networkRequests: Int
	)

	private data class TerrainElevationWork(
		val tileId: TerrainTileId,
		val refinement: Boolean,
		val distanceKm: Double,
		val complexity: Long
	)

	private data class TerrainElevationResult(
		val work: TerrainElevationWork,
		val result: Result<LoadedTerrainTile>
	)

	private data class SatelliteTextureWork(
		val tileId: TerrainTileId,
		val quality: FlightSatelliteQuality,
		val distanceKm: Double,
		val complexity: Long
	)

	private data class SatelliteTextureResult(
		val work: SatelliteTextureWork,
		val result: Result<LoadedSatelliteTexture>
	)

	private data class TerrainRefinementLayer(
		val plan: TerrainTilePlan,
		val quadsByTile: Map<TerrainTileId, Int>
	)

	private class DownloadRateTracker {
		private val samples = ArrayDeque<Pair<Long, Long>>()

		fun record(bytes: Long) {
			if (bytes <= 0L) return
			val now = System.nanoTime()
			samples.addLast(now to bytes)
			prune(now)
		}

		fun bytesPerSecond(): Long {
			val now = System.nanoTime()
			prune(now)
			if (samples.isEmpty()) return 0L
			val duration = (now - samples.peekFirst().first).coerceAtLeast(1_000_000_000L)
			return samples.sumOf { it.second } * 1_000_000_000L / duration
		}

		private fun prune(now: Long) {
			while (samples.isNotEmpty() && now - samples.peekFirst().first > DOWNLOAD_RATE_WINDOW_NANOS) {
				samples.removeFirst()
			}
		}
	}

	companion object {
		const val TERRAIN_DIRECTORY = "flight-terrain/terrarium"
		private const val TERRARIUM_BASE_URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium"
		private const val PARTIAL_SUFFIX = ".download"
		private const val PARALLEL_DOWNLOADS = 6
		private const val SATELLITE_STANDARD_PARALLEL_DOWNLOADS = 4
		private const val DETAIL_PARALLEL_DOWNLOADS = 2
		private const val SCENE_PUBLISH_INTERVAL_NANOS = 250_000_000L
		private const val SCENE_PUBLISH_TILE_BATCH = 4
		private const val SATELLITE_FAILURE_CIRCUIT_BREAKER = PARALLEL_DOWNLOADS
		private const val CONNECT_TIMEOUT_MILLIS = 15_000
		private const val READ_TIMEOUT_MILLIS = 30_000
		private const val SATELLITE_CONNECT_TIMEOUT_MILLIS = 8_000
		private const val SATELLITE_READ_TIMEOUT_MILLIS = 15_000
		private const val DOWNLOAD_BUFFER_SIZE = 16 * 1_024
		private const val CANCELLATION_CHECK_PIXEL_INTERVAL = 4_096
		private const val MAX_TILE_BYTES = 4L * 1_024L * 1_024L
		private const val COMPOSITE_JPEG_QUALITY = 92
		private const val MAXIMUM_DECODED_TERRAIN_TILES = 384
		private const val MAXIMUM_GEOMETRY_CACHE_TILES = 384
		private const val MAXIMUM_GEOMETRY_CACHE_BYTES = 128L * 1_024L * 1_024L
		private const val MAXIMUM_LOD_HISTORY_TILES = 2_048
		private const val LOD_HYSTERESIS_FACTOR = 1.18
		private const val PRIORITY_DISTANCE_BUCKET_KM = 5.0
		private val SATELLITE_WORK_COMPARATOR = compareBy<SatelliteTextureWork>(
			{ (it.distanceKm / PRIORITY_DISTANCE_BUCKET_KM).toInt() },
			{ it.complexity },
			{ it.distanceKm },
			{ it.tileId.y },
			{ it.tileId.x }
		)
		// Keeps a continental trip resident while bounding float-coordinate error in
		// the GLES 2.0 view and shadow matrices on very long flights.
		private const val COORDINATE_ORIGIN_RESET_DISTANCE_KM = 2_000.0
		private const val ESTIMATED_PLACEHOLDER_GEOMETRY_BYTES_PER_TILE = 156L
		private const val FLOAT_BYTES = 4L
		private const val SHORT_BYTES = 2L
		private const val DOWNLOAD_RATE_WINDOW_NANOS = 5_000_000_000L
	}
}
