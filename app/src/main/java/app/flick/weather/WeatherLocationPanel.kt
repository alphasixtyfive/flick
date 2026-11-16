package app.flick.weather

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.ImeAction
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.components.NativeTextField
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow

@Composable
internal fun WeatherLocationPanel(location: WeatherLocation?, places: List<PlaceResult>, searching: Boolean,
    onSearch: (String) -> Unit, onSelect: (WeatherLocation?) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    TvPanel("Weather location", onBack = onBack) {
        location?.let { Text("Current: ${it.name}", style = MaterialTheme.typography.bodyMedium) }
        NativeTextField("Search city or town", query, { entered ->
            query = entered.trim().take(100)
            onSearch(query)
        }, imeAction = ImeAction.Search)
        when {
            query.length == 1 -> Text("Enter at least two characters.", style = MaterialTheme.typography.bodyMedium)
            query.length >= 2 && searching -> Text("Searching…", style = MaterialTheme.typography.bodyMedium)
            query.length >= 2 && places.isEmpty() -> Text("No places found. Try another city or town.",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (query.length >= 2 && !searching) places.forEach { place ->
            TvSettingRow(place.name, detail = place.region, icon = Icons.Default.LocationOn, onClick = {
                onSelect(WeatherLocation(place.latitude, place.longitude,
                    listOf(place.name, place.region).filter(String::isNotBlank).joinToString(", ").take(100), location?.fahrenheit ?: false))
                onBack()
            })
        }
        if (query.isNotEmpty()) TvSettingRow("Clear search", icon = Icons.Default.Close,
            onClick = { query = ""; onSearch("") })
        if (location != null) TvSettingRow("Clear location", icon = Icons.Default.Refresh,
            onClick = { onSelect(null); onBack() })
    }
}
