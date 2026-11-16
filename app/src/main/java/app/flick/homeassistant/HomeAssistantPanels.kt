package app.flick.homeassistant

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.components.NativeTextField
import app.flick.ui.components.TvLazyPanel
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.components.TvStatusRow
import app.flick.ui.icons.FlickIcons
import app.flick.ui.theme.FlickColors

@Composable
internal fun HomeAssistantControlsPanel(controls: List<HaControl>, connectionState: HaConnectionState,
    onPower: (HaControl, Boolean) -> Unit, onScene: (HaControl) -> Unit, onRetry: () -> Unit,
    onChooseControls: () -> Unit, onBack: () -> Unit) {
    TvPanel("Home Assistant", onBack = onBack, focusKey = connectionState to controls.isEmpty()) {
        if (connectionState != HaConnectionState.Connected) {
            Text(connectionState.label, style = MaterialTheme.typography.bodySmall)
            if (connectionState == HaConnectionState.Offline) TvSettingRow("Retry", icon = Icons.Default.Refresh, onClick = onRetry)
        }
        if (controls.isEmpty()) {
            Text("Choose the lights, switches and scenes you want to use here.", style = MaterialTheme.typography.bodyMedium)
            TvSettingRow("Choose controls", icon = Icons.Default.Tune, onClick = onChooseControls)
        }
        controls.forEach { control -> key(control.id) {
            val scene = control.kind in HaPolicy.actionDomains
            val available = control.state != "unavailable" && (scene || control.state !in listOf("unknown", null))
            val on = control.state == "on"
            val toggle = control.kind in HaPolicy.powerDomains
            val detail = when {
                control.pending -> if (scene) "Running…" else "Updating…"
                connectionState != HaConnectionState.Connected -> "Waiting for Home Assistant"
                !available -> "Unavailable"
                control.error != null -> if (scene) "Couldn’t run · Select to retry" else "Couldn’t update · Select to retry"
                scene -> "Run scene"
                control.brightness != null && on -> "On · ${control.brightness}%"
                toggle -> if (on) "On" else "Off"
                else -> control.state.orEmpty().replaceFirstChar { it.uppercase() }
            }
            val icon = when {
                scene -> Icons.Default.PlayArrow
                control.kind == "light" -> Icons.Default.Lightbulb
                toggle -> Icons.Default.PowerSettingsNew
                control.kind == "sensor" || control.kind == "binary_sensor" -> Icons.Default.Sensors
                else -> Icons.Default.Info
            }
            if ((scene || toggle) && available && connectionState == HaConnectionState.Connected) {
                TvSettingRow(control.name, detail = detail, checked = if (toggle) on else null,
                    icon = icon,
                    onClick = {
                        if (!control.pending) {
                            if (scene) onScene(control) else onPower(control, !on)
                        }
                    })
            } else TvStatusRow(control.name, detail = detail, icon = icon)
        } }
    }
}

@Composable
internal fun HomeAssistantSetupPanel(configuration: HaConfiguration, connectionState: HaConnectionState, busy: Boolean,
    onConfigure: (String, String) -> Unit, onRetry: () -> Unit, onBack: () -> Unit, onChooseControls: () -> Unit,
    onIntegration: () -> Unit,
    displayPower: HaDisplayPower = if (configuration.displayPowerEntity == null) HaDisplayPower.Disabled else HaDisplayPower.Unknown,
    onDiscoverDisplayPlayers: (suspend () -> List<HaEntity>)? = null,
    onSaveDisplayPowerEntity: (suspend (HaControlSpec?) -> Unit)? = null) {
    var choosingDisplay by remember(configuration.url, configuration.configured) { mutableStateOf(false) }
    if (choosingDisplay && onDiscoverDisplayPlayers != null && onSaveDisplayPowerEntity != null) {
        HomeDisplayPowerPicker(configuration.displayPowerEntity, onDiscoverDisplayPlayers, onSaveDisplayPowerEntity,
            onBack = { choosingDisplay = false })
        return
    }
    var url by remember { mutableStateOf(configuration.url) }
    var token by remember { mutableStateOf("") }
    LaunchedEffect(configuration.url, configuration.configured) {
        if (configuration.configured) { url = configuration.url; token = "" }
    }
    TvPanel("Home Assistant", onBack = onBack, focusKey = configuration.configured to connectionState) {
        if (configuration.configured) {
            Text("${connectionState.label} · ${configuration.url}", style = MaterialTheme.typography.bodySmall,
                color = FlickColors.Secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (connectionState == HaConnectionState.Offline) TvSettingRow(if (busy) "Retrying…" else "Retry",
                icon = Icons.Default.Refresh, onClick = { if (!busy) onRetry() })
            val count = configuration.controls.size
            TvSettingRow("Choose controls", detail = when (count) {
                0 -> "Add lights, switches and scenes"
                1 -> "1 control selected"
                else -> "$count controls selected"
            }, icon = Icons.Default.Tune,
                onClick = { if (!busy) onChooseControls() })
            if (onDiscoverDisplayPlayers != null && onSaveDisplayPowerEntity != null) {
                val status = when (displayPower) {
                    HaDisplayPower.Disabled -> "Not selected"
                    HaDisplayPower.Unknown -> "State unavailable"
                    HaDisplayPower.On -> "TV on"
                    HaDisplayPower.Off -> "TV off"
                }
                TvSettingRow("Physical TV", detail = configuration.displayPowerEntity?.let { "${it.name} · $status" }
                    ?: "Optional · Detect when your TV turns off", icon = Icons.Default.Tv,
                    onClick = { if (!busy) choosingDisplay = true })
            }
            TvSettingRow("TV integration", detail = "Optional · Notifications and TV management", icon = Icons.Default.Tv,
                onClick = onIntegration)
            TvSettingRow("Disconnect", icon = Icons.Default.LinkOff,
                onClick = { if (!busy) onConfigure("", "") })
        } else {
            Text("Connect your lights, switches and scenes.", style = MaterialTheme.typography.bodyMedium)
            NativeTextField("Home Assistant address", url, { url = it.take(500) })
            NativeTextField("Access token", token, { token = it.take(4096) }, secret = true)
            TvSettingRow(if (busy) "Connecting…" else "Connect", icon = FlickIcons.HomeAssistant,
                onClick = { if (!busy && url.isNotBlank() && token.isNotBlank()) onConfigure(url.trim(), token) })
            Text("Use a separate, non-admin account. In its Home Assistant profile, open Security and create a long-lived access token. It stays private on this TV.",
                style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary)
        }
    }
}

/** Unseen existing selections remain intact until the user explicitly deselects them. */
internal fun selectedControlSpecs(entities: List<HaEntity>, selected: Set<String>, existing: List<HaControlSpec>): List<HaControlSpec> {
    val knownIds = entities.mapTo(mutableSetOf()) { it.entityId }
    val saved = existing.associateBy { it.entityId }
    val visible = entities.filter { it.entityId in selected }.map { saved[it.entityId] ?: HaControlSpec(it.entityId, it.name) }
    val unseen = existing.filter { it.entityId !in knownIds && it.entityId in selected }
    return (visible + unseen).distinctBy { it.entityId }
}

/** Existing selections can always be removed, including when an imported list exceeds the limit. */
internal fun toggleControlSelection(selected: Set<String>, entityId: String): Set<String>? = when {
    entityId in selected -> selected - entityId
    selected.size < HaPolicy.maxControls -> selected + entityId
    else -> null
}

private data class ControlSaveCompletion(val request: Int, val result: Result<Unit>)

@Composable
internal fun HomeEntityPicker(entities: List<HaEntity>, configured: List<HaControlSpec>, busy: Boolean, loaded: Boolean,
    onSave: (List<HaControlSpec>, (Result<Unit>) -> Unit) -> Unit, showMessage: (String) -> Unit, onBack: () -> Unit) {
    var selected by remember(configured) { mutableStateOf(configured.map { it.entityId }.toSet()) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saveRequest by remember { mutableIntStateOf(0) }
    var completion by remember { mutableStateOf<ControlSaveCompletion?>(null) }
    LaunchedEffect(completion) {
        val finished = completion ?: return@LaunchedEffect
        if (finished.request != saveRequest) return@LaunchedEffect
        saving = false
        finished.result.fold(onSuccess = { onBack() }, onFailure = {
            saveError = it.message?.take(160) ?: "The selection could not be saved. Try again."
        })
    }
    val toggleSelection: (String) -> Unit = { entityId ->
        if (!saving) {
            val next = toggleControlSelection(selected, entityId)
            if (next == null) showMessage("Choose at most ${HaPolicy.maxControls} controls")
            else { selected = next; saveError = null }
        }
    }
    val unseen = configured.distinctBy { it.entityId }.filter { spec -> entities.none { it.entityId == spec.entityId } }
    TvLazyPanel("Choose controls", onBack = onBack) {
        item(key = "save") {
            TvSettingRow(if (saving) "Saving…" else "Save selection", detail = when {
                saving -> "Wait for the selection to be saved"
                busy -> "Loading devices…"
                !loaded -> "Load devices before saving"
                selected.size == 1 -> "1 control selected"
                else -> "${selected.size} controls selected"
            },
                icon = Icons.Default.Check, onClick = {
                    when {
                        saving -> Unit
                        busy || !loaded -> showMessage("Wait for devices to load before saving.")
                        selected.size > HaPolicy.maxControls -> saveError = "Choose at most ${HaPolicy.maxControls} controls"
                        else -> {
                            saving = true
                            saveError = null
                            val request = ++saveRequest
                            onSave(selectedControlSpecs(entities, selected, configured)) { result ->
                                completion = ControlSaveCompletion(request, result)
                            }
                        }
                    }
                })
        }
        saveError?.let { error -> item(key = "save-error") {
            Text(error, style = MaterialTheme.typography.bodySmall)
        } }
        if (busy && !saving) item(key = "loading") { Text("Loading available devices…", style = MaterialTheme.typography.bodyMedium) }
        else if (!loaded) item(key = "failed") { Text("Devices have not loaded. Go back and try again; your selection is unchanged.", style = MaterialTheme.typography.bodyMedium) }
        else if (entities.isEmpty()) item(key = "empty") { Text("No eligible devices were found.", style = MaterialTheme.typography.bodyMedium) }
        items(entities, key = { it.entityId }) { entity ->
            TvSettingRow(entity.name, detail = entity.domain.replace('_', ' ').replaceFirstChar { it.uppercase() }, checked = entity.entityId in selected,
                onClick = { toggleSelection(entity.entityId) })
        }
        items(unseen, key = { "unseen:" + it.entityId }) { spec ->
            TvSettingRow(spec.name, detail = "Not in the current device list", checked = spec.entityId in selected,
                onClick = { toggleSelection(spec.entityId) })
        }
    }
}
