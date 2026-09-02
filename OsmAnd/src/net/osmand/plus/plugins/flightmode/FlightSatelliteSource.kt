package net.osmand.plus.plugins.flightmode

object FlightSatelliteSource {

	const val CACHE_DIRECTORY = "flight-terrain/satellite-eox-s2cloudless-2016"
	const val RENDER_CACHE_DIRECTORY = "flight-terrain/satellite-render"
	const val ATTRIBUTION = "Sentinel-2 cloudless — https://s2maps.eu by EOX IT Services GmbH " +
		"(Contains modified Copernicus Sentinel data 2016 & 2017)"

	fun tileUrl(tileId: TerrainTileId): String =
		"$WMTS_BASE_URL/${tileId.zoom}/${tileId.y}/${tileId.x}.jpg"

	fun renderDirectory(quality: FlightSatelliteQuality): String =
		if (quality == FlightSatelliteQuality.STANDARD) CACHE_DIRECTORY
		else "$RENDER_CACHE_DIRECTORY/${quality.name.lowercase()}"

	private const val WMTS_BASE_URL =
		"https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g"
}
