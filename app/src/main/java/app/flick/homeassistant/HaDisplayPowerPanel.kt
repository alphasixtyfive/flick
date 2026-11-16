package app.flick.homeassistant

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.*
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.components.TvLazyPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.theme.FlickColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** This selects an observation source, never a control that can turn the television on or off. */
@Composable
internal fun HomeDisplayPowerPicker(selected: HaControlSpec?, discover: suspend () -> List<HaEntity>,
    save: suspend (HaControlSpec?) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var entities by remember { mutableStateOf<List<HaEntity>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loading = true
        error = null
        try { entities = discover() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = (failure as? HaException)?.message ?: "TVs could not be loaded. Try again." }
        finally { loading = false }
    }
    fun choose(value: HaControlSpec?) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try { save(value); onBack() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = (failure as? HaException)?.message ?: "The TV selection could not be saved." }
            finally { saving = false }
        }
    }
    TvLazyPanel("Physical TV", onBack = onBack, focusKey = loading) {
        item(key = "explanation") {
            Text("Choose the screen, not this Android box or an audio player. With Ask on startup, turning the TV off ends the current profile session.",
                style = MaterialTheme.typography.bodyMedium)
        }
        item(key = "none") {
            TvSettingRow("Not selected", detail = "Use Android screen-off detection only", checked = selected == null,
                icon = Icons.Default.Tv, onClick = { choose(null) })
        }
        if (loading) item(key = "loading") { Text("Loading TVs…", style = MaterialTheme.typography.bodySmall) }
        error?.let { message -> item(key = "error") { Text(message, style = MaterialTheme.typography.bodySmall) } }
        if (!loading && error != null) item(key = "retry") {
            TvSettingRow("Retry", icon = Icons.Default.Refresh, onClick = { if (!saving) attempt++ })
        }
        items(entities.orEmpty(), key = { it.entityId }) { entity ->
            TvSettingRow(entity.name, detail = entity.entityId, checked = entity.entityId == selected?.entityId,
                icon = Icons.Default.Tv, onClick = { choose(HaControlSpec(entity.entityId, entity.name)) })
        }
        if (!loading && entities?.isEmpty() == true) item(key = "empty") {
            Text("No media players were found. Check which devices your Home Assistant account can read.", style = MaterialTheme.typography.bodySmall)
        }
        item(key = "offline") {
            Text("TV off ends the session. An unavailable state or lost connection also ends it once. Local profiles and PINs still work offline; exact physical-TV wake detection needs a connected source.",
                style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary)
        }
    }
}
