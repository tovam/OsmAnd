package net.osmand.plus.search.smart;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * High-confidence shortcuts that should not spend time in the language model.
 * The returned query is always the user's original text: this class only
 * classifies, it never rewrites an address.
 */
public final class SmartSearchPreprocessor {

	private static final Pattern COORDINATES = Pattern.compile(
			"^-?\\d{1,2}(?:[.,]\\d+)\\s*[,;]\\s*-?\\d{1,3}(?:[.,]\\d+)$");
	private static final Pattern PLUS_CODE = Pattern.compile(
			"^[0-9A-Z]{4,8}\\+[0-9A-Z]{2,3}(?:\\s+.+)?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern NUMBERED_ADDRESS = Pattern.compile(
			"^\\d{1,4}(?:\\s*(?:bis|ter|quater))?\\s+"
					+ "(?:rue|avenue|av\\.?|boulevard|bd\\.?|chemin|impasse|place|all[ée]e|quai|cours|route|passage|square|voie)\\b.+$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern BARE_STREET = Pattern.compile(
			"^(?:rue|avenue|av\\.?|boulevard|bd\\.?|chemin|impasse|place|all[ée]e|quai|cours|route|passage|square|voie|rond-point)\\s+\\S.+$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern ROAD_REFERENCE = Pattern.compile(
			"^(?:[dn]\\s*\\d{1,4}|(?:route\\s+)?(?:nationale|d[ée]partementale)\\s+\\d{1,4})(?:\\s+.+)?$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Pattern AMBIGUOUS_NON_ADDRESS = Pattern.compile(
			"^(?:place\\s+(?:de|pour)\\s+(?:parking|stationner|se garer)|"
					+ "cours\\s+(?:de|d['’])\\s*(?:yoga|pilates|danse|sport|natation|cuisine|anglais|fran[cç]ais)|"
					+ "route\\s+(?:sans|avec|la plus|le plus|alternative|rapide|courte|[ée]conomique|[ée]vitant|vers)\\b).*$",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private SmartSearchPreprocessor() {
	}

	/** Returns a direct OsmAnd location query, or {@code null} when the LLM is needed. */
	@Nullable
	public static String directLocationQuery(@Nullable String userText) {
		if (userText == null) {
			return null;
		}
		String query = userText.trim();
		if (query.isEmpty() || query.length() > 300) {
			return null;
		}
		String normalized = normalize(query);
		if (AMBIGUOUS_NON_ADDRESS.matcher(normalized).matches()) {
			return null;
		}
		if (COORDINATES.matcher(query).matches()
				|| PLUS_CODE.matcher(query).matches()
				|| NUMBERED_ADDRESS.matcher(query).matches()
				|| BARE_STREET.matcher(query).matches()
				|| ROAD_REFERENCE.matcher(query).matches()) {
			return query;
		}
		return null;
	}

	@NonNull
	private static String normalize(@NonNull String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ")
				.trim();
	}
}
