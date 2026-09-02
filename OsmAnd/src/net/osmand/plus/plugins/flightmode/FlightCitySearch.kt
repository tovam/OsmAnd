package net.osmand.plus.plugins.flightmode

import net.osmand.CollatorStringMatcher.StringMatcherMode.CHECK_STARTS_FROM_SPACE
import net.osmand.ResultMatcher
import net.osmand.data.Amenity
import net.osmand.plus.OsmandApplication
import net.osmand.search.core.SearchPhrase.NameStringMatcher
import net.osmand.shared.util.KMapUtils
import java.util.Locale
import kotlin.math.roundToInt

/** Searches only the cities contained in the user's installed OsmAnd map indexes. */
internal class FlightCitySearch(private val app: OsmandApplication) {

	fun search(query: String, cancelled: () -> Boolean): List<FlightCitySuggestion> {
		val normalizedQuery = query.trim()
		if (normalizedQuery.length < MINIMUM_QUERY_LENGTH) return emptyList()

		val matcher = NameStringMatcher(normalizedQuery, CHECK_STARTS_FROM_SPACE)
		val language = app.settings.MAP_PREFERRED_LOCALE.get()
		val transliterate = app.settings.MAP_TRANSLITERATE_NAMES.get()
		val location = app.locationProvider.lastKnownLocation
		val matches = mutableListOf<CityMatch>()
		var scanned = 0

		app.resourceManager.amenitySearcher.searchAmenitiesByName(
			normalizedQuery,
			KMapUtils.MAX_LATITUDE,
			KMapUtils.MIN_LONGITUDE,
			KMapUtils.MIN_LATITUDE,
			KMapUtils.MAX_LONGITUDE,
			location?.latitude ?: 0.0,
			location?.longitude ?: 0.0,
			object : ResultMatcher<Amenity> {
				override fun publish(amenity: Amenity): Boolean {
					scanned++
					if (scanned > MAXIMUM_SCANNED_RESULTS) return false
					if (amenity.subType !in CITY_SUB_TYPES) return false

					val localizedName = amenity.getName(language, transliterate)
					val otherNames = amenity.getOtherNames(true)
					if (!matcher.matches(localizedName) && !matcher.matches(otherNames)) return false
					val point = amenity.location ?: return false
					val displayName = localizedName?.takeIf { it.isNotBlank() }
						?: amenity.name?.takeIf { it.isNotBlank() }
						?: return false
					matches += CityMatch(
						suggestion = FlightCitySuggestion(
							name = displayName,
							latitude = point.latitude,
							longitude = point.longitude,
							subType = amenity.subType,
							regionName = amenity.regionName?.takeIf { it.isNotBlank() }
						),
						matchRank = matchRank(displayName, otherNames, normalizedQuery),
						population = amenity.getAdditionalInfo(Amenity.POPULATION)?.toLongOrNull() ?: 0L
					)
					return false
				}

				override fun isCancelled(): Boolean {
					return cancelled() || scanned > MAXIMUM_SCANNED_RESULTS
				}
			}
		)

		return matches
			.sortedWith(
				compareBy<CityMatch> { it.matchRank }
					.thenBy { CITY_SUB_TYPES.indexOf(it.suggestion.subType) }
					.thenByDescending { it.population }
					.thenBy { it.suggestion.name.lowercase(Locale.ROOT) }
			)
			.distinctBy {
				Triple(
					it.suggestion.name.lowercase(Locale.ROOT),
					(it.suggestion.latitude * 100).roundToInt(),
					(it.suggestion.longitude * 100).roundToInt()
				)
			}
			.take(MAXIMUM_SUGGESTIONS)
			.map { it.suggestion }
	}

	private fun matchRank(name: String, otherNames: List<String>, query: String): Int = when {
		name.equals(query, ignoreCase = true) || otherNames.any { it.equals(query, ignoreCase = true) } -> 0
		name.startsWith(query, ignoreCase = true) -> 1
		otherNames.any { it.startsWith(query, ignoreCase = true) } -> 2
		else -> 3
	}

	private data class CityMatch(
		val suggestion: FlightCitySuggestion,
		val matchRank: Int,
		val population: Long
	)

	companion object {
		private const val MINIMUM_QUERY_LENGTH = 2
		private const val MAXIMUM_SCANNED_RESULTS = 500
		private const val MAXIMUM_SUGGESTIONS = 8
		private val CITY_SUB_TYPES = listOf("city", "town", "village")
	}
}
