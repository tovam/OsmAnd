package net.osmand.plus.plugins.flightmode

/**
 * Screen-oriented texture LODs. Geometry never depends on this policy: changing quality only
 * changes the texture selected for an already resident terrain tile.
 */
object FlightTerrainLodPolicy {

	/**
	 * Keeps an already rendered texture from visibly stepping down while any part of
	 * its base tile is still within 50 km of the aircraft. The caller supplies the
	 * distance to the nearest point of the tile, not its centre.
	 */
	fun shouldRetainNearbyDetail(
		previous: FlightTerrainTextureTier?,
		raw: FlightTerrainTextureTier,
		aircraftNearestDistanceKm: Double
	): Boolean = previous != null && raw.ordinal < previous.ordinal &&
		aircraftNearestDistanceKm <= NEARBY_DETAIL_RETENTION_KM

	/**
	 * Combines the normal aircraft-centred rings with an optional gaze-centred ring.
	 * The gaze centre is at least High even when the global display is Standard.
	 */
	fun tierForFoci(
		requested: FlightSatelliteQuality,
		radiusKm: Int,
		aircraftDistanceKm: Double,
		detailFocusDistanceKm: Double?
	): FlightTerrainTextureTier {
		val aircraftTier = tierForDistance(requested, radiusKm, aircraftDistanceKm)
		val focusQuality = if (requested.ordinal < FlightSatelliteQuality.HIGH.ordinal) {
			FlightSatelliteQuality.HIGH
		} else requested
		val focusTier = detailFocusDistanceKm?.let { distance ->
			tierForDistance(focusQuality, radiusKm, distance)
		}
		return if (focusTier != null && focusTier.ordinal > aircraftTier.ordinal) focusTier else aircraftTier
	}

	fun tierForDistance(
		requested: FlightSatelliteQuality,
		radiusKm: Int,
		distanceKm: Double
	): FlightTerrainTextureTier {
		val radius = radiusKm.coerceAtLeast(1).toDouble()
		val fraction = (distanceKm / radius).coerceAtLeast(0.0)
		return when (requested) {
			FlightSatelliteQuality.STANDARD -> when {
				fraction <= 0.65 -> FlightTerrainTextureTier.STANDARD
				else -> FlightTerrainTextureTier.OVERVIEW
			}
			FlightSatelliteQuality.HIGH -> when {
				fraction <= 0.20 -> FlightTerrainTextureTier.HIGH
				fraction <= 0.62 -> FlightTerrainTextureTier.STANDARD
				else -> FlightTerrainTextureTier.OVERVIEW
			}
			FlightSatelliteQuality.ULTRA -> when {
				fraction <= 0.067 -> FlightTerrainTextureTier.ULTRA
				fraction <= 0.235 -> FlightTerrainTextureTier.HIGH
				fraction <= 0.57 -> FlightTerrainTextureTier.STANDARD
				else -> FlightTerrainTextureTier.OVERVIEW
			}
			FlightSatelliteQuality.ULTRA_PLUS -> when {
				fraction <= 0.03 -> FlightTerrainTextureTier.ULTRA_PLUS
				// The base terrain tiles are roughly 50 km wide for a 300 km scene.
				// A wider second ring guarantees that Ultra is not skipped between the
				// central Ultra+ tile footprint and the High ring.
				fraction <= 0.18 -> FlightTerrainTextureTier.ULTRA
				fraction <= 0.36 -> FlightTerrainTextureTier.HIGH
				fraction <= 0.68 -> FlightTerrainTextureTier.STANDARD
				else -> FlightTerrainTextureTier.OVERVIEW
			}
			FlightSatelliteQuality.ULTRA_PLUS_PLUS -> when {
				fraction <= 0.014 -> FlightTerrainTextureTier.ULTRA_PLUS_PLUS
				fraction <= 0.045 -> FlightTerrainTextureTier.ULTRA_PLUS
				fraction <= 0.18 -> FlightTerrainTextureTier.ULTRA
				fraction <= 0.36 -> FlightTerrainTextureTier.HIGH
				fraction <= 0.68 -> FlightTerrainTextureTier.STANDARD
				else -> FlightTerrainTextureTier.OVERVIEW
			}
			FlightSatelliteQuality.ULTRA_PLUS_PLUS_PLUS -> when {
				fraction <= 0.007 -> FlightTerrainTextureTier.ULTRA_PLUS_PLUS_PLUS
				fraction <= 0.020 -> FlightTerrainTextureTier.ULTRA_PLUS_PLUS
				fraction <= 0.055 -> FlightTerrainTextureTier.ULTRA_PLUS
				fraction <= 0.18 -> FlightTerrainTextureTier.ULTRA
				fraction <= 0.36 -> FlightTerrainTextureTier.HIGH
				fraction <= 0.68 -> FlightTerrainTextureTier.STANDARD
				else -> FlightTerrainTextureTier.OVERVIEW
			}
		}
	}

	fun satelliteQuality(tier: FlightTerrainTextureTier): FlightSatelliteQuality? = when (tier) {
		FlightTerrainTextureTier.OVERVIEW -> null
		FlightTerrainTextureTier.STANDARD -> FlightSatelliteQuality.STANDARD
		FlightTerrainTextureTier.HIGH -> FlightSatelliteQuality.HIGH
		FlightTerrainTextureTier.ULTRA -> FlightSatelliteQuality.ULTRA
		FlightTerrainTextureTier.ULTRA_PLUS -> FlightSatelliteQuality.ULTRA_PLUS
		FlightTerrainTextureTier.ULTRA_PLUS_PLUS -> FlightSatelliteQuality.ULTRA_PLUS_PLUS
		FlightTerrainTextureTier.ULTRA_PLUS_PLUS_PLUS -> FlightSatelliteQuality.ULTRA_PLUS_PLUS_PLUS
	}

	fun estimatedTextureBytes(tier: FlightTerrainTextureTier): Long {
		if (tier == FlightTerrainTextureTier.OVERVIEW) return 0L
		val zoomDelta = satelliteQuality(tier)?.zoomDelta ?: 0
		val edge = STANDARD_TEXTURE_EDGE shl zoomDelta
		val baseBytes = edge.toLong() * edge * 2L
		// The renderer keeps mipmaps through 4096². At 8192² it deliberately preserves
		// the full-resolution base image without a 33% mip-chain memory surcharge.
		return if (edge <= MAXIMUM_MIPMAPPED_EDGE) baseBytes * 4L / 3L else baseBytes
	}

	private const val STANDARD_TEXTURE_EDGE = 256
	private const val MAXIMUM_MIPMAPPED_EDGE = 4_096
	const val NEARBY_DETAIL_RETENTION_KM = FlightSceneStreamingPolicy.NEARBY_RESOURCE_RETENTION_KM
}
