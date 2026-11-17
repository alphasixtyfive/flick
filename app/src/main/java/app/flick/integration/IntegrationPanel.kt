package app.flick.integration

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.home.HomeActions
import app.flick.home.HomeState
import app.flick.ui.components.TvDialog
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.theme.FlickColors

private enum class IntegrationPage(val title: String) {
    Overview("TV connection"), Notifications("Notifications"),
    Management("Launcher management"),
}

@Composable
internal fun IntegrationPanel(state: HomeState, actions: HomeActions, onBack: () -> Unit) {
    val bridge = state.bridge
    var page by remember { mutableStateOf(IntegrationPage.Overview) }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(bridge.paired) {
        if (!bridge.paired) { page = IntegrationPage.Overview; confirmForget = false }
    }
    val back = { if (page == IntegrationPage.Overview) onBack() else page = IntegrationPage.Overview }
    TvPanel(page.title, onBack = back, focusKey = page to bridge.paired) {
        when (page) {
            IntegrationPage.Overview -> {
                if (!bridge.paired) {
                    IntegrationDetail(bridge.connection.label)
                    IntegrationDetail("Add Flick in Home Assistant to send notifications and manage this TV. Light controls work without it.")
                    if (state.haConfiguration.configured) {
                        val code = bridge.pairingCode
                        if (code != null) {
                            Text(code, style = MaterialTheme.typography.headlineMedium)
                            IntegrationDetail("In Home Assistant, open Settings → Devices & services → Add integration → Flick TV. Enter this code within five minutes.")
                        }
                        TvSettingRow(if (code == null) "Pair TV" else "New pairing code", icon = Icons.Default.Link,
                            onClick = actions.pairHomeAssistant)
                    } else {
                        IntegrationDetail("Connect Home Assistant first, then return here to pair this TV.")
                    }
                    TvSettingRow("Setup instructions", icon = Icons.AutoMirrored.Filled.HelpOutline,
                        onClick = { actions.openProjectLink("https://github.com/alphasixtyfive/flick#home-assistant") })
                } else {
                    TvSettingRow("Enable TV connection", checked = bridge.enabled, icon = Icons.Default.Link,
                        detail = if (bridge.enabled) bridge.connection.label else "Paused. Pairing is saved.",
                        onClick = { actions.setIntegrationEnabled(!bridge.enabled) })
                    TvSettingRow("Notifications", detail = when {
                        !bridge.notificationsEnabled -> "Off"
                        !bridge.enabled -> "Paused"
                        !state.notifications.overlayAllowed -> "Android permission needed"
                        bridge.connection != BridgeConnection.Connected -> "Waiting for connection"
                        else -> "On"
                    }, icon = Icons.Default.Notifications, onClick = { page = IntegrationPage.Notifications })
                    TvSettingRow("Launcher management", detail = if (bridge.allowManagement) "Allowed" else "Off",
                        icon = Icons.Default.ManageAccounts, onClick = { page = IntegrationPage.Management })
                    TvSettingRow("Unpair TV", icon = Icons.Default.LinkOff, onClick = { confirmForget = true })
                }
            }
            IntegrationPage.Notifications -> {
                IntegrationDetail("Show messages, photos and actions from Home Assistant over your apps.")
                TvSettingRow("Notifications", checked = bridge.notificationsEnabled, icon = Icons.Default.Notifications,
                    onClick = { actions.setNotificationsEnabled(!bridge.notificationsEnabled) })
                TvSettingRow("Display over other apps", detail = if (state.notifications.overlayAllowed) "Allowed by Android" else "Allow in Android settings",
                    icon = Icons.Default.PictureInPicture, onClick = actions.requestOverlayPermission)
                if (!bridge.enabled) IntegrationDetail("TV connection is paused. Enable it to receive notifications.")
                state.notifications.message?.let { IntegrationDetail(it) }
            }
            IntegrationPage.Management -> {
                IntegrationDetail("Let Home Assistant administrators manage profiles and wallpapers while an adult profile is unlocked. Light controls and notifications work separately.")
                TvSettingRow("Allow launcher management", checked = bridge.allowManagement, icon = Icons.Default.ManageAccounts,
                    onClick = { actions.setLauncherManagement(!bridge.allowManagement) })
                if (!bridge.enabled) IntegrationDetail("TV connection is paused. Your choice is saved until you enable it.")
            }
        }
    }
    if (confirmForget) TvDialog("Unpair this TV?", onDismiss = { confirmForget = false }) {
        Text("Stop Home screen text, notifications and launcher management. Your light controls stay connected.",
            style = MaterialTheme.typography.bodyMedium)
        TvSettingRow("Cancel", onClick = { confirmForget = false })
        TvSettingRow("Unpair TV", onClick = { confirmForget = false; actions.forgetIntegration() })
    }
}

@Composable
private fun IntegrationDetail(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary)
}
