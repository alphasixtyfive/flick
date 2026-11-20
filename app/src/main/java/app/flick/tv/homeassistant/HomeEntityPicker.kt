package app.flick.tv.homeassistant

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.tv.ui.components.TvLazyPanel
import app.flick.tv.ui.components.TvPanel
import app.flick.tv.ui.components.TvSettingRow

@Composable
internal fun HomeEntityPicker(entities: List<HaEntity>, configured: List<HaControlSpec>, busy: Boolean, loaded: Boolean,
    onToggle: (HaControlSpec, Boolean, (Result<Unit>) -> Unit) -> Unit,
    onRetry: () -> Unit, showMessage: (String) -> Unit, onBack: () -> Unit) {
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    val selected = configured.mapTo(mutableSetOf()) { it.entityId }
    pending.forEach { (id, enabled) -> if (enabled) selected.add(id) else selected.remove(id) }
    var group by remember { mutableStateOf<HaEntityGroup?>(null) }
    var lastGroup by remember { mutableStateOf(HaEntityGroup.Selected) }
    val groupFocus = remember { FocusRequester() }

    val groups = remember(entities) { groupControlEntities(entities) }
    val page = group
    if (page != null) {
        // Keep an unchecked row in place until Back so it can be reselected without losing remote focus.
        val rows = if (page == HaEntityGroup.Selected) {
            remember { selectedControlSpecs(entities, selected, configured).alphabetical() }
        } else groups[page].orEmpty()
        val ambiguousIds = remember(rows) { ambiguousControlIds(rows) }
        val discoveredIds = remember(entities) { entities.mapTo(mutableSetOf()) { it.entityId } }
        TvLazyPanel(page.title, onBack = { group = null }) {
            if (rows.isEmpty()) item { Text("No controls selected.", style = MaterialTheme.typography.bodyMedium) }
            items(rows, key = { it.entityId }) { control ->
                val unavailable = loaded && control.entityId !in discoveredIds
                val detail = when {
                    control.entityId in ambiguousIds && unavailable -> "${control.entityId}\nUnavailable"
                    control.entityId in ambiguousIds -> control.entityId
                    unavailable -> "Unavailable"
                    else -> ""
                }
                val id = control.entityId
                TvSettingRow(control.name, detail = detail, checked = id in selected, busy = id in pending, onClick = {
                    if (id !in pending) {
                        val next = toggleControlSelection(selected, id)
                        if (next == null) showMessage("Choose at most ${HaPolicy.maxControls} controls")
                        else {
                            val enabled = id in next
                            pending[id] = enabled
                            onToggle(control, enabled) { result ->
                                pending.remove(id)
                                result.onFailure { showMessage(it.message?.take(160) ?: "Couldn’t update this control. Try again.") }
                            }
                        }
                    }
                })
            }
        }
        return
    }

    TvPanel("Choose controls", onBack = onBack, preferredFocus = groupFocus) {
        for (choice in HaEntityGroup.entries) {
            if (choice != HaEntityGroup.Selected && choice !in groups) continue
            val icon = when (choice) {
                HaEntityGroup.Selected -> Icons.Default.Checklist
                HaEntityGroup.Cameras -> Icons.Default.Videocam
                HaEntityGroup.Lights -> Icons.Default.Lightbulb
                HaEntityGroup.Switches -> Icons.Default.PowerSettingsNew
                HaEntityGroup.Actions -> Icons.Default.PlayArrow
                HaEntityGroup.Sensors -> Icons.Default.Sensors
            }
            TvSettingRow(choice.title, detail = if (choice == HaEntityGroup.Selected) "${selected.size} selected" else "",
                icon = icon, modifier = if (choice == lastGroup) Modifier.focusRequester(groupFocus) else Modifier,
                onClick = {
                    lastGroup = choice
                    group = choice
                })
        }
        if (busy && !loaded) Text("Loading available devices…", style = MaterialTheme.typography.bodyMedium)
        else if (!loaded) TvSettingRow("Retry device list", icon = Icons.Default.Refresh, onClick = onRetry)
        else if (groups.isEmpty()) Text("No eligible devices were found.", style = MaterialTheme.typography.bodyMedium)
    }
}
