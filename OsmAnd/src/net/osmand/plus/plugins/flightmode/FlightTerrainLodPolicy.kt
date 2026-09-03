package net.osmand.plus.plugins.flightmode

/**
 * Screen-oriented texture LODs. Geometry never depends on this policy: changing quality only
 * changes the texture selected for an already resident terrain tile.
 */
object FlightTerrainLodPolicy {

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
		}
	}

	fun satelliteQuality(tier: FlightTerrainTextureTier): FlightSatelliteQuality? = when (tier) {
		FlightTerrainTextureTier.OVERVIEW -> null
		FlightTerrainTextureTier.STANDARD -> FlightSatelliteQuality.STANDARD
		FlightTerrainTextureTier.HIGH -> FlightSatelliteQuality.HIGH
		FlightTerrainTextureTier.ULTRA -> FlightSatelliteQuality.ULTRA
		FlightTerrainTextureTier.ULTRA_PLUS -> FlightSatelliteQuality.ULTRA_PLUS
	}

	fun estimatedTextureBytes(tier: FlightTerrainTextureTier): Long {
		if (tier == FlightTerrainTextureTier.OVERVIEW) return 0L
		val zoomDelta = satelliteQuality(tier)?.zoomDelta ?: 0
		val edge = STANDARD_TEXTURE_EDGE shl zoomDelta
		// RGB_565 plus the complete mip chain (approximately another third).
		return edge.toLong() * edge * 2L * 4L / 3L
	}

	private const val STANDARD_TEXTURE_EDGE = 256
}
