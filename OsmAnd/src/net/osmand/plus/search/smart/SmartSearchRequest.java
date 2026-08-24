package net.osmand.plus.search.smart;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

public final class SmartSearchRequest {

	public enum Kind { LOCATION, POI }
	public enum Context { CURRENT_LOCATION, MAP_CENTER, DESTINATION, ROUTE, NAMED_PLACE, UNSPECIFIED }
	public enum ResultMode { ALL, NEAREST, NEXT, LAST }
	public enum Availability { ANY, OPEN_NOW, OPEN_24_7, OPEN_AT_ARRIVAL }

	@NonNull public final Kind kind;
	@Nullable public final String query;
	@Nullable public final String name;
	@Nullable public final String category;
	@Nullable public final Context context;
	@Nullable public final String place;
	@Nullable public final ResultMode resultMode;
	@Nullable public final Availability availability;

	private SmartSearchRequest(@NonNull Kind kind, @Nullable String query, @Nullable String name,
	                           @Nullable String category, @Nullable Context context,
	                           @Nullable String place, @Nullable ResultMode resultMode,
	                           @Nullable Availability availability) {
		this.kind = kind;
		this.query = query;
		this.name = name;
		this.category = category;
		this.context = context;
		this.place = place;
		this.resultMode = resultMode;
		this.availability = availability;
	}

	@NonNull
	public static SmartSearchRequest location(@NonNull String query) {
		String cleanQuery = query.trim();
		if (cleanQuery.isEmpty()) {
			throw new IllegalArgumentException("Empty location query");
		}
		return new SmartSearchRequest(Kind.LOCATION, cleanQuery, null, null,
				null, null, null, null);
	}

	@NonNull
	public static SmartSearchRequest poi(@Nullable String name, @Nullable String category,
	                                     @NonNull String context, @Nullable String place,
	                                     @NonNull String resultMode, @NonNull String availability) {
		String cleanName = clean(name);
		String cleanCategory = clean(category);
		if ((cleanName == null) == (cleanCategory == null)) {
			throw new IllegalArgumentException("Exactly one of name and category is required");
		}
		Context parsedContext = enumValue(Context.class, context);
		ResultMode parsedMode = enumValue(ResultMode.class, resultMode);
		Availability parsedAvailability = enumValue(Availability.class, availability);
		String cleanPlace = clean(place);
		if (parsedContext == Context.NAMED_PLACE && cleanPlace == null) {
			throw new IllegalArgumentException("NAMED_PLACE requires place");
		}
		if (parsedContext != Context.NAMED_PLACE && cleanPlace != null) {
			throw new IllegalArgumentException("place is only valid with NAMED_PLACE");
		}
		if ((parsedMode == ResultMode.NEXT || parsedMode == ResultMode.LAST)
				&& parsedContext != Context.ROUTE) {
			throw new IllegalArgumentException(parsedMode + " requires ROUTE");
		}
		if (parsedContext == Context.ROUTE && parsedMode == ResultMode.NEAREST) {
			throw new IllegalArgumentException("ROUTE cannot use NEAREST");
		}
		if (parsedAvailability == Availability.OPEN_AT_ARRIVAL
				&& parsedContext != Context.ROUTE && parsedContext != Context.DESTINATION) {
			throw new IllegalArgumentException("OPEN_AT_ARRIVAL requires ROUTE or DESTINATION");
		}
		return new SmartSearchRequest(Kind.POI, null, cleanName, cleanCategory,
				parsedContext, cleanPlace, parsedMode, parsedAvailability);
	}

	@Nullable
	private static String clean(@Nullable String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim();
	}

	@NonNull
	private static <T extends Enum<T>> T enumValue(@NonNull Class<T> type, @NonNull String value) {
		try {
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Invalid " + type.getSimpleName() + ": " + value, e);
		}
	}
}
