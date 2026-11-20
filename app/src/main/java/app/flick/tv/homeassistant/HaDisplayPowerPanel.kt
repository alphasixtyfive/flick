package app.flick.tv.homeassistant

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.tv.ui.components.TvLazyPanel
import app.flick.tv.ui.components.TvSettingRow
import app.flick.tv.ui.components.TvChoiceRow
import app.flick.tv.ui.theme.FlickColors
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
    TvLazyPanel("TV standby detection", onBack = onBack, focusKey = loading) {
        item(key = "explanation") {
            Text("Android normally detects screen-off itself. Choose a Home Assistant TV only if the box stays awake when the screen turns off. With Ask on startup, TV standby ends the current profile session.",
                style = MaterialTheme.typography.bodyMedium)
        }
        item(key = "none") {
            TvChoiceRow("Android screen-off", selected = selected == null, onClick = { choose(null) })
        }
        if (loading) item(key = "loading") { Text("Loading TVs…", style = MaterialTheme.typography.bodySmall) }
        error?.let { message -> item(key = "error") { Text(message, style = MaterialTheme.typography.bodySmall) } }
        if (!loading && error != null) item(key = "retry") {
            TvSettingRow("Retry", icon = Icons.Default.Refresh, onClick = { if (!saving) attempt++ })
        }
        items(entities.orEmpty(), key = { it.entityId }) { entity ->
            TvChoiceRow(entity.name, selected = entity.entityId == selected?.entityId,
                onClick = { choose(HaControlSpec(entity.entityId, entity.name)) })
        }
        if (!loading && selected != null && entities.orEmpty().none { it.entityId == selected.entityId }) item(key = "saved") {
            TvChoiceRow("${selected.name} (saved)", selected = true, onClick = { choose(selected) })
        }
        if (!loading && entities?.isEmpty() == true) item(key = "empty") {
            Text("No TVs were found. The source must be a media player classified as a TV in Home Assistant.", style = MaterialTheme.typography.bodySmall)
        }
        item(key = "offline") {
            Text("TV off ends the session. An unavailable state or lost connection also ends it once. Local profiles and PINs still work offline; exact physical-TV wake detection needs a connected source.",
                style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary)
        }
    }
}
