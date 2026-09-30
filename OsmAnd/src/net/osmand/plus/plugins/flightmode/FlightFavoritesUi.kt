package net.osmand.plus.plugins.flightmode

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.osmand.plus.R

private class FlightFavoritesStore(context: Context) {
    private val preferences by lazy {
        context.getSharedPreferences("flight-library-favorites", Context.MODE_PRIVATE)
    }

    fun load() =
        FlightFavorites(
            preferences.getStringSet("journeys", emptySet()).orEmpty().toSet(),
            preferences.getStringSet("photos", emptySet()).orEmpty().toSet(),
        )

    fun save(favorites: FlightFavorites) {
        preferences
            .edit()
            .putStringSet("journeys", favorites.journeys)
            .putStringSet("photos", favorites.photos)
            .apply()
    }
}

internal data class FlightFavoritesUi(
    val favorites: FlightFavorites = FlightFavorites(),
    val ready: Boolean = false,
    val toggleJourney: (FlightLibraryRow) -> Unit = {},
    val togglePhoto: (String) -> Unit = {},
)

internal val LocalFlightFavorites = staticCompositionLocalOf { FlightFavoritesUi() }

@Composable
internal fun rememberFlightFavorites(): FlightFavoritesUi {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { FlightFavoritesStore(context) }
    var favorites by remember(store) { mutableStateOf<FlightFavorites?>(null) }
    LaunchedEffect(store) { favorites = withContext(Dispatchers.IO) { store.load() } }
    fun update(transform: (FlightFavorites) -> FlightFavorites) {
        favorites?.let { current ->
            val next = transform(current)
            favorites = next
            store.save(next)
        }
    }
    return FlightFavoritesUi(
        favorites ?: FlightFavorites(),
        favorites != null,
        { row -> update { it.toggle(row) } },
        { id -> update { it.togglePhoto(id) } },
    )
}

@Composable
internal fun FlightFavoriteButton(
    selected: Boolean,
    ready: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    filter: Boolean = false,
) {
    IconToggleButton(
        checked = selected,
        onCheckedChange = { onToggle() },
        enabled = ready,
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            painterResource(
                if (selected) R.drawable.ic_action_favorite
                else R.drawable.ic_action_favorite_stroke
            ),
            contentDescription =
                stringResource(
                    if (filter) R.string.flight_favorites_only
                    else if (selected) R.string.flight_favorite_remove
                    else R.string.flight_favorite_add
                ),
            tint = if (selected) Color(0xFFFFCC66) else Color(0xFF99ADB9),
            modifier = Modifier.size(20.dp),
        )
    }
}
