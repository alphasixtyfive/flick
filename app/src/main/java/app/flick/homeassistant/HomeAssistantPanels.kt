package app.flick.homeassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.components.NativeTextField
import app.flick.ui.components.TvDialog
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.components.TvStatusRow
import app.flick.ui.icons.FlickIcons
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout
import kotlinx.coroutines.delay

@Composable
internal fun HomeAssistantControlsPanel(controls: List<HaControl>, connectionState: HaConnectionState,
    onPower: (HaControl, Boolean) -> Unit, onScene: (HaControl) -> Unit, onCamera: (String) -> Unit, onRetry: () -> Unit,
    onChooseControls: () -> Unit, onBack: () -> Unit) {
    TvPanel("Home Assistant", onBack = onBack, focusKey = connectionState to controls.isEmpty()) {
        if (connectionState != HaConnectionState.Connected) {
            Text(connectionState.label, style = MaterialTheme.typography.bodySmall)
            if (connectionState == HaConnectionState.Offline) TvSettingRow("Retry", icon = Icons.Default.Refresh, onClick = onRetry)
        }
        if (controls.isEmpty()) {
            Text("Choose the devices and scenes you want to use here.", style = MaterialTheme.typography.bodyMedium)
            TvSettingRow("Choose controls", icon = Icons.Default.Tune, onClick = onChooseControls)
        }
        controls.forEach { control -> key(control.id) {
            val camera = control.kind == "camera"
            val scene = control.kind in HaPolicy.actionDomains
            val toggle = control.kind in HaPolicy.powerDomains
            val available = when {
                toggle -> control.state == "on" || control.state == "off"
                scene -> control.state != "unavailable"
                camera && control.state == "off" -> false
                else -> control.state !in listOf("unknown", "unavailable", null)
            }
            val connected = connectionState == HaConnectionState.Connected
            val on = control.state == "on"
            val displayedOn = control.displayedOn
            var showPending by remember { mutableStateOf(false) }
            LaunchedEffect(control.pending, control.requestedOn) {
                showPending = false
                if (control.pending) {
                    delay(800)
                    showPending = true
                }
            }
            val detail = when {
                !connected -> "Waiting for Home Assistant"
                !available -> "Unavailable"
                camera -> "View camera"
                !control.pending && control.error != null -> if (scene) "Couldn’t run. Try again." else "Couldn’t update. Try again."
                scene && control.pending && showPending -> "Running…"
                scene -> if (control.kind == "script") "Run script" else "Run scene"
                control.brightness != null && on && displayedOn == true -> "Brightness ${control.brightness}%"
                toggle -> if (displayedOn == true) "On" else "Off"
                else -> control.state.orEmpty().replaceFirstChar { it.uppercase() }
            }
            val icon = when {
                camera -> Icons.Default.Videocam
                scene -> Icons.Default.PlayArrow
                control.kind == "light" -> Icons.Default.Lightbulb
                toggle -> Icons.Default.PowerSettingsNew
                control.kind == "sensor" || control.kind == "binary_sensor" -> Icons.Default.Sensors
                else -> Icons.Default.Info
            }
            if ((camera || scene || toggle) && available && connected) {
                TvSettingRow(control.name, detail = detail, checked = if (toggle) displayedOn else null,
                    icon = icon, busy = toggle && control.pending && showPending,
                    modifier = Modifier.semantics {
                        if (control.pending) stateDescription = when (control.requestedOn) {
                            true -> "Turning on"
                            false -> "Turning off"
                            null -> "Running"
                        }
                    },
                    onClick = {
                        if (!control.pending) {
                            when {
                                camera -> onCamera(control.id)
                                scene -> onScene(control)
                                else -> onPower(control, !on)
                            }
                        }
                    })
            } else TvStatusRow(control.name, detail = detail, icon = icon)
        } }
    }
}

@Composable
internal fun HomeAssistantSetupPanel(configuration: HaConfiguration, connectionState: HaConnectionState, busy: Boolean,
    onConfigure: (String, String) -> Unit, onRetry: () -> Unit, onBack: () -> Unit, onChooseControls: () -> Unit,
    onIntegration: () -> Unit, onHomeText: () -> Unit, connectionDetail: String) {
    var url by remember { mutableStateOf(configuration.url) }
    var token by remember { mutableStateOf("") }
    var confirmDisconnect by remember(configuration.url) { mutableStateOf(false) }
    LaunchedEffect(configuration.url, configuration.configured) {
        if (configuration.configured) { url = configuration.url; token = "" }
    }
    TvPanel("Home Assistant", onBack = onBack, focusKey = configuration.configured to connectionState) {
        if (configuration.configured) {
            Column(Modifier.padding(start = TvLayout.RowTextInset, end = TvLayout.RowHorizontalPadding),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(connectionState.label, style = MaterialTheme.typography.bodySmall)
                Text(configuration.url, style = MaterialTheme.typography.bodySmall,
                    color = FlickColors.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (connectionState == HaConnectionState.Offline) TvSettingRow(if (busy) "Retrying…" else "Retry",
                icon = Icons.Default.Refresh, onClick = { if (!busy) onRetry() })
            val count = configuration.controls.size
            TvSettingRow("Controls", detail = when (count) {
                0 -> "Choose devices and scenes"
                1 -> "1 control selected"
                else -> "$count controls selected"
            }, icon = Icons.Default.Tune,
                onClick = { if (!busy) onChooseControls() })
            TvSettingRow("TV connection", detail = connectionDetail, icon = Icons.Default.Tv,
                onClick = onIntegration)
        } else {
            Text("Connect your lights, switches and scenes.", style = MaterialTheme.typography.bodyMedium)
            NativeTextField("Home Assistant address", url, { url = it.take(500) })
            NativeTextField("Access token", token, { token = it.take(4096) }, secret = true)
            TvSettingRow(if (busy) "Connecting…" else "Connect", icon = FlickIcons.HomeAssistant,
                onClick = { if (!busy && url.isNotBlank() && token.isNotBlank()) onConfigure(url.trim(), token) })
            Text("Use a separate, non-admin account. In its Home Assistant profile, open Security and create a long-lived access token. It stays private on this TV.",
                style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary)
        }
        TvSettingRow("Home screen text", icon = Icons.Default.Info,
            onClick = onHomeText)
        if (configuration.configured) TvSettingRow("Disconnect", icon = Icons.Default.LinkOff,
            onClick = { if (!busy) confirmDisconnect = true })
    }
    if (configuration.configured && confirmDisconnect) {
        TvDialog("Disconnect Home Assistant?", onDismiss = { confirmDisconnect = false }) {
            Text("This removes the saved connection and sidebar controls from this TV.", style = MaterialTheme.typography.bodyMedium)
            TvSettingRow("Cancel", onClick = { confirmDisconnect = false })
            TvSettingRow("Disconnect", icon = Icons.Default.LinkOff, onClick = {
                confirmDisconnect = false
                if (!busy) onConfigure("", "")
            })
        }
    }
}
