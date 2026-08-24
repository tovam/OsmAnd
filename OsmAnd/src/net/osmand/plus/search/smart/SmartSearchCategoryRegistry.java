package net.osmand.plus.search.smart;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class SmartSearchCategoryRegistry {

	public static final class Definition {
		@NonNull public final String category;
		@Nullable public final String osmandPoiType;
		@Nullable public final String osmandAdditionalType;
		@NonNull public final String fallbackQuery;

		private Definition(@NonNull String category, @Nullable String osmandPoiType,
		                   @Nullable String osmandAdditionalType, @NonNull String fallbackQuery) {
			this.category = category;
			this.osmandPoiType = osmandPoiType;
			this.osmandAdditionalType = osmandAdditionalType;
			this.fallbackQuery = fallbackQuery;
		}
	}

	private static volatile SmartSearchCategoryRegistry instance;
	private final Map<String, Definition> definitions;

	private SmartSearchCategoryRegistry(@NonNull Context context) {
		definitions = load(context.getApplicationContext());
	}

	@NonNull
	public static SmartSearchCategoryRegistry get(@NonNull Context context) {
		SmartSearchCategoryRegistry result = instance;
		if (result == null) {
			synchronized (SmartSearchCategoryRegistry.class) {
				result = instance;
				if (result == null) {
					result = new SmartSearchCategoryRegistry(context);
					instance = result;
				}
			}
		}
		return result;
	}

	@Nullable
	public Definition find(@Nullable String category) {
		return category == null ? null : definitions.get(category);
	}

	@NonNull
	public String fallbackQuery(@NonNull String category) {
		Definition definition = find(category);
		return definition != null ? definition.fallbackQuery : category.replace('_', ' ');
	}

	@NonNull
	private static Map<String, Definition> load(@NonNull Context context) {
		Map<String, Definition> result = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				context.getAssets().open("smart_search_categories.json"), StandardCharsets.UTF_8))) {
			StringBuilder json = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				json.append(line).append('\n');
			}
			JSONObject root = new JSONObject(json.toString());
			Iterator<String> keys = root.keys();
			while (keys.hasNext()) {
				String category = keys.next();
				JSONObject value = root.getJSONObject(category);
				String fallback = value.optString("fallback_query", category.replace('_', ' '));
				result.put(category, new Definition(category,
						nullIfEmpty(value.optString("osmand_poi_type", null)),
						nullIfEmpty(value.optString("osmand_additional_type", null)), fallback));
			}
		} catch (IOException | JSONException e) {
			throw new IllegalStateException("Unable to load smart-search category registry", e);
		}
		return result;
	}

	@Nullable
	private static String nullIfEmpty(@Nullable String value) {
		return value == null || value.trim().isEmpty() || "null".equals(value) ? null : value;
	}
}
