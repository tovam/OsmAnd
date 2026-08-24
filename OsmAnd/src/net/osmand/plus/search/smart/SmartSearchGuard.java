package net.osmand.plus.search.smart;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small product-owned safety layer around the model output. It deliberately
 * handles only high-confidence French cues; everything semantic that is not
 * explicit remains the model's job.
 */
public final class SmartSearchGuard {

	private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
	private static final Map<String, String> LITERAL_EXCEPTIONS = new LinkedHashMap<>();
	private static final Map<String, Pattern> CATEGORY_RULES = new LinkedHashMap<>();

	private static final Pattern QUOTED_NAME = Pattern.compile("[«\"]([^»\"]{2,80})[»\"]");
	private static final Pattern NAMED_PLACE = Pattern.compile(
			"(?:^|\\s)(?:a|vers|autour de|pres de)\\s+([A-Z][\\p{L}'’ -]{1,60}?)(?:[,.!?;:]|$)");

	private static final Pattern ROUTE_CUE = words(
			"sur (?:ma|la|notre) route|sur (?:mon|l')itineraire|sur (?:mon|le) trajet|"
					+ "le long (?:de|du)|sur le chemin|devant (?:moi|nous)|dans (?:mon|le) sens|"
					+ "sans (?:revenir|retourner) en arriere|sans demi tour|avant la destination|"
					+ "avant d'arriver|avant d'atteindre|portion restante|trajet|itineraire|parcours|guidage");
	private static final Pattern DESTINATION_CUE = words(
			"a destination|pres de (?:ma |la )?destination|autour de (?:ma |la )?destination|"
					+ "a l'arrivee|vers l'arrivee|pres de l'arrivee|point d'arrivee|point final|terminus|"
					+ "fin du trajet|fin du guidage|quand j'arrive|quand nous arrivons|quand j'y serai");
	private static final Pattern MAP_CUE = words(
			"sur la carte|zone (?:visible|actuelle|affichee)|sur l'ecran|a l'ecran|"
					+ "portion visible|centre de la carte|autour du centre de la carte");
	private static final Pattern CURRENT_CUE = words(
			"pres de moi|proche de moi|autour de moi|a proximite de moi|a cote de moi|pres d'ici|ma position|position actuelle|"
					+ "la ou je suis|la ou je me trouve|dans les parages|dans mon secteur|ici|d'ici");

	private static final Pattern LAST_CUE = words(
			"dernier|derniere|ultime|le plus pres de la fin|le plus proche de la fin|"
					+ "le plus pres de la destination|le plus proche de la destination");
	private static final Pattern NEXT_CUE = words(
			"prochain|prochaine|premier|premiere|suivant|suivante|devant (?:moi|nous)|"
					+ "sans (?:revenir|retourner) en arriere|sans demi tour");
	private static final Pattern NEAREST_CUE = words(
			"le plus proche|la plus proche|les plus proches|le moins loin|la moins loin|"
					+ "le moins eloigne|la moins eloignee");

	private static final Pattern OPEN_24_7_CUE = Pattern.compile(
			"(?:\\b24\\s*h(?:eures?)?\\s*(?:sur|/)\\s*24\\b|\\b24/7\\b)", FLAGS);
	private static final Pattern OPEN_NOW_CUE = words(
			"ouvert(?:e|s|es)? maintenant|disponible(?:s)? maintenant|"
					+ "ouvert(?:e|s|es)? en ce moment|ouvert(?:e|s|es)? tout de suite");
	private static final Pattern OPEN_AT_ARRIVAL_CUE = Pattern.compile(
			"\\b(?:ouvert(?:e|s|es)?|disponible(?:s)?)\\b.*\\b(?:quand (?:j'|nous )?(?:arrive|arrivons|y passe)|"
					+ "a (?:mon )?arrivee|a destination|au moment (?:de mon passage|ou j'arrive))\\b", FLAGS);

	static {
		LITERAL_EXCEPTIONS.put("au bureau", "Au Bureau");
		LITERAL_EXCEPTIONS.put("carglass", "Carglass");

		category("ev_charging", "borne(?:s)? de recharge|recharge electrique|recharger (?:la |ma )?(?:voiture|vehicule)|brancher (?:la |ma )?(?:voiture|vehicule)");
		category("fuel_station", "station(?: |-)?service|station(?:s)? essence|faire le plein|pompe(?:s)? a essence|mettre de l'essence");
		category("bakery", "boulangerie|acheter (?:une )?baguette|prendre (?:une )?baguette|acheter du pain|prendre du pain|pain frais");
		category("pharmacy", "pharmacie|officine|acheter des medicaments|trouver des medicaments");
		category("supermarket", "supermarche|faire (?:mes|les|des) courses|magasin alimentaire|acheter de quoi manger");
		category("parking", "parking|stationnement|me garer|garer (?:la |ma )?(?:voiture|vehicule)|stationner (?:la |ma )?(?:voiture|vehicule)");
		category("toilets", "toilettes(?: publiques)?|wc|sanitaires");
		category("atm", "distributeur de billets|retirer de l'argent|retirer des especes|retirer du liquide|dab");
		category("car_repair", "garage automobile|reparateur auto|mecano|reparer (?:la |ma )?(?:voiture|vehicule)|vitrage automobile|pare-brise");
		category("laundry", "laverie|laver (?:mon|le|du) linge|machine a laver|faire une machine");
		category("playground", "aire de jeux|terrain de jeux|balancoire|toboggan|faire jouer les enfants");
		category("bookshop", "librairie|acheter (?:un|des) livres|trouver (?:un|des) livres");
		category("butcher", "boucherie|acheter de la viande|prendre de la viande|chez le boucher");
		category("hardware_store", "magasin de bricolage|magasin d'outils|acheter des outils|materiel de bricolage");
		category("shopping_mall", "centre commercial|faire les magasins");
		category("restaurant", "restaurant|resto|endroit pour manger|ou manger|dejeuner|diner");
		category("cafe", "cafe|expresso|pause cafe|petit noir");
		category("hotel", "hotel|endroit pour dormir|chambre pour la nuit|hebergement");
		category("hospital", "hopital|urgences hospitalieres");
		category("defibrillator", "defibrillateur");
	}

	private SmartSearchGuard() {
	}

	@NonNull
	public static SmartSearchRequest guardLocation(@NonNull String userText,
	                                               @NonNull String modelQuery) {
		String exact = exactUserSpan(userText.trim(), modelQuery);
		if (exact == null) {
			throw new IllegalArgumentException("Le modèle a inventé un lieu absent de la demande");
		}
		return SmartSearchRequest.location(exact);
	}

	@NonNull
	public static SmartSearchRequest guardPoi(@NonNull String userText,
	                                          @Nullable String modelName,
	                                          @Nullable String modelCategory,
	                                          @Nullable String modelContext,
	                                          @Nullable String modelPlace,
	                                          @Nullable String modelResultMode,
	                                          @Nullable String modelAvailability,
	                                          @NonNull SmartSearchCategoryRegistry registry) {
		String user = userText.trim();
		String normalized = normalize(user);
		String literalName = literalException(normalized);
		if (literalName == null) {
			literalName = quotedName(user);
		}
		if (literalName == null) {
			literalName = exactUserSpan(user, modelName);
		}

		String inferredCategory = inferCategory(normalized);
		String category = literalName == null && registry.find(inferredCategory) != null
				? inferredCategory : null;
		if (category != null && literalName == null && registry.find(modelCategory) != null
				&& isSpecificVariant(category, modelCategory)) {
			category = modelCategory;
		}
		if (literalName == null && category == null && registry.find(modelCategory) != null) {
			category = modelCategory;
		}
		if (literalName == null && category == null) {
			throw new IllegalArgumentException("Le modèle n’a fourni ni nom recopié ni catégorie OsmAnd connue");
		}

		SmartSearchRequest.Context context = inferContext(user, normalized, modelContext, modelPlace);
		String place = context == SmartSearchRequest.Context.NAMED_PLACE
				? exactUserSpan(user, modelPlace) : null;
		if (context == SmartSearchRequest.Context.NAMED_PLACE && place == null) {
			place = inferNamedPlace(user);
		}
		if (context == SmartSearchRequest.Context.NAMED_PLACE && place == null) {
			context = SmartSearchRequest.Context.UNSPECIFIED;
		}

		SmartSearchRequest.ResultMode resultMode = inferResultMode(normalized, context);
		SmartSearchRequest.Availability availability = inferAvailability(normalized, context);
		return SmartSearchRequest.poi(literalName, category, context.name(), place,
				resultMode.name(), availability.name());
	}

	@NonNull
	private static SmartSearchRequest.Context inferContext(@NonNull String user,
	                                                       @NonNull String normalized,
	                                                       @Nullable String modelContext,
	                                                       @Nullable String modelPlace) {
		if (ROUTE_CUE.matcher(normalized).find()) {
			return SmartSearchRequest.Context.ROUTE;
		}
		if (MAP_CUE.matcher(normalized).find()) {
			return SmartSearchRequest.Context.MAP_CENTER;
		}
		if (CURRENT_CUE.matcher(normalized).find()) {
			return SmartSearchRequest.Context.CURRENT_LOCATION;
		}
		if (DESTINATION_CUE.matcher(normalized).find()) {
			return SmartSearchRequest.Context.DESTINATION;
		}
		if ("NAMED_PLACE".equalsIgnoreCase(modelContext) && exactUserSpan(user, modelPlace) != null) {
			return SmartSearchRequest.Context.NAMED_PLACE;
		}
		if (inferNamedPlace(user) != null) {
			return SmartSearchRequest.Context.NAMED_PLACE;
		}
		return SmartSearchRequest.Context.UNSPECIFIED;
	}

	@NonNull
	private static SmartSearchRequest.ResultMode inferResultMode(@NonNull String normalized,
	                                                             @NonNull SmartSearchRequest.Context context) {
		if (context == SmartSearchRequest.Context.ROUTE) {
			if (LAST_CUE.matcher(normalized).find()) {
				return SmartSearchRequest.ResultMode.LAST;
			}
			if (NEXT_CUE.matcher(normalized).find()) {
				return SmartSearchRequest.ResultMode.NEXT;
			}
			return SmartSearchRequest.ResultMode.ALL;
		}
		return NEAREST_CUE.matcher(normalized).find()
				? SmartSearchRequest.ResultMode.NEAREST : SmartSearchRequest.ResultMode.ALL;
	}

	@NonNull
	private static SmartSearchRequest.Availability inferAvailability(
			@NonNull String normalized, @NonNull SmartSearchRequest.Context context) {
		if (OPEN_24_7_CUE.matcher(normalized).find()) {
			return SmartSearchRequest.Availability.OPEN_24_7;
		}
		if ((context == SmartSearchRequest.Context.ROUTE
				|| context == SmartSearchRequest.Context.DESTINATION)
				&& OPEN_AT_ARRIVAL_CUE.matcher(normalized).find()) {
			return SmartSearchRequest.Availability.OPEN_AT_ARRIVAL;
		}
		if (OPEN_NOW_CUE.matcher(normalized).find()) {
			return SmartSearchRequest.Availability.OPEN_NOW;
		}
		return SmartSearchRequest.Availability.ANY;
	}

	@Nullable
	private static String inferCategory(@NonNull String normalized) {
		for (Map.Entry<String, Pattern> entry : CATEGORY_RULES.entrySet()) {
			if (entry.getValue().matcher(normalized).find()) {
				return entry.getKey();
			}
		}
		return null;
	}

	private static boolean isSpecificVariant(@NonNull String inferred, @NonNull String modelCategory) {
		if (inferred.equals(modelCategory)) {
			return true;
		}
		return switch (inferred) {
			case "restaurant" -> modelCategory.startsWith("restaurant_")
					|| "pizzeria".equals(modelCategory) || "sushi_restaurant".equals(modelCategory);
			case "parking" -> modelCategory.startsWith("parking_") || "park_and_ride".equals(modelCategory);
			case "toilets" -> modelCategory.startsWith("toilets_");
			case "fuel_station" -> modelCategory.startsWith("fuel_");
			case "ev_charging" -> modelCategory.startsWith("ev_charging_");
			default -> false;
		};
	}

	@Nullable
	private static String literalException(@NonNull String normalized) {
		String padded = " " + normalized + " ";
		for (Map.Entry<String, String> entry : LITERAL_EXCEPTIONS.entrySet()) {
			if (padded.contains(" " + entry.getKey() + " ")) {
				return entry.getValue();
			}
		}
		return null;
	}

	@Nullable
	private static String quotedName(@NonNull String user) {
		Matcher matcher = QUOTED_NAME.matcher(user);
		return matcher.find() ? matcher.group(1).trim() : null;
	}

	@Nullable
	private static String inferNamedPlace(@NonNull String user) {
		Matcher matcher = NAMED_PLACE.matcher(user);
		return matcher.find() ? matcher.group(1).trim() : null;
	}

	@Nullable
	private static String exactUserSpan(@NonNull String user, @Nullable String proposed) {
		if (proposed == null || proposed.trim().isEmpty()) {
			return null;
		}
		String needle = proposed.trim();
		for (int index = 0; index + needle.length() <= user.length(); index++) {
			if (user.regionMatches(true, index, needle, 0, needle.length())) {
				return user.substring(index, index + needle.length());
			}
		}
		return null;
	}

	private static void category(@NonNull String category, @NonNull String alternatives) {
		CATEGORY_RULES.put(category, Pattern.compile("\\b(?:" + alternatives + ")\\b", FLAGS));
	}

	@NonNull
	private static Pattern words(@NonNull String alternatives) {
		return Pattern.compile("\\b(?:" + alternatives + ")\\b", FLAGS);
	}

	@NonNull
	private static String normalize(@NonNull String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "")
				.replace('’', '\'')
				.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9&'+.-]+", " ")
				.replaceAll("\\s+", " ")
				.trim();
	}
}
