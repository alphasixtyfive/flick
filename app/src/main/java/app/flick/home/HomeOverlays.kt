package app.flick.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import app.flick.apps.AppEntry
import app.flick.profiles.ProfilePicker
import app.flick.profiles.ProfilePin
import app.flick.profiles.ProfilePickerBackdrop
import app.flick.ui.components.TvDialog
import app.flick.ui.components.TvSettingRow

internal fun HomePanel.isOverlay() = this is HomePanel.AppOptions || this is HomePanel.ContinueOptions ||
    this == HomePanel.Profiles || this is HomePanel.Pin || this == HomePanel.AllApps

@Composable
internal fun HomeOverlays(panel: HomePanel, state: HomeState, actions: HomeActions, allAppsScroll: LazyGridState,
    isCurrent: () -> Boolean, onClose: () -> Unit, onAppOptionsBack: () -> Unit, onPanel: (HomePanel) -> Unit,
    onMove: (AppEntry) -> Unit, onRemoveContinue: () -> Unit,
    focusedApp: String? = null, onFocusedApp: (String) -> Unit = {}) {
    // The page stays mounted with its scroll and focus target while its menu is on top.
    if (panel == HomePanel.AllApps || panel is HomePanel.AppOptions && panel.fromAllApps) {
        AllAppsDrawer(state, actions, allAppsScroll, focusedApp, onFocusedApp, onPanel, onClose,
            isInteractive = { panel == HomePanel.AllApps && isCurrent() })
    }
    when (panel) {
        is HomePanel.AppOptions -> TvDialog(panel.app.name, onDismiss = onAppOptionsBack) {
            val favorites = state.settings.favorites ?: state.apps.take(5).map { it.packageName }
            val child = HomePolicy.profile(state.settings).child
            val pinned = panel.app.packageName in HomePolicy.homeAppPackages(state.settings, state.apps)
            if (pinned) TvSettingRow(if (panel.fromAllApps) "Move on Home" else "Move", icon = Icons.Default.SwapHoriz,
                onClick = { runAppOptionsAction(isCurrent) { onMove(panel.app) } })
            if (!child) {
                TvSettingRow(if (pinned) "Remove from Home" else "Add to Home", icon = if (pinned) Icons.Default.Remove else Icons.Default.Add,
                    onClick = {
                        runAppOptionsAction(isCurrent) {
                            val updated = if (pinned) favorites - panel.app.packageName else HomePolicy.addFavorite(favorites, panel.app.packageName)
                            if (updated == null) actions.showMessage("Home supports up to ${HomePolicy.MAX_FAVORITES} apps. Remove one before adding another.")
                            else { actions.updateSettings(state.settings, state.settings.copy(favorites = updated)); onAppOptionsBack() }
                        }
                    })
                TvSettingRow("App info", icon = Icons.Default.Info,
                    onClick = { runAppOptionsAction(isCurrent) { onAppOptionsBack(); actions.appInfo(panel.app) } })
                if (panel.app.canUninstall) TvSettingRow("Uninstall", icon = Icons.Default.Delete,
                    onClick = { runAppOptionsAction(isCurrent) { onAppOptionsBack(); actions.uninstallApp(panel.app) } })
            }
        }
        is HomePanel.ContinueOptions -> TvDialog("", onDismiss = onClose, width = 300.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(model = panel.item.artwork, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp, 49.5.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)))
                Text(panel.item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TvSettingRow("Remove from Continue watching", icon = Icons.Default.VisibilityOff, onClick = onRemoveContinue)
        }
        HomePanel.Profiles -> ProfilePicker(state.settings.profiles, state.settings.activeProfileId,
            onChoose = { profile ->
                val current = HomePolicy.profile(state.settings)
                if (profile.id == current.id) onClose()
                else if (!profile.child && (current.child || profile.pinHash != null)) onPanel(HomePanel.Pin(profile))
                else actions.chooseProfile(profile, "") { result ->
                    result.fold(onSuccess = { onClose() }, onFailure = {
                        actions.showMessage(it.message?.take(160) ?: "This profile could not be opened")
                    })
                }
            }, onDismiss = onClose)
        is HomePanel.Pin -> Box(Modifier.fillMaxSize()) {
            ProfilePickerBackdrop()
            ProfilePin(panel.profile, state.profileError.takeIf { state.profileErrorTarget == panel.profile.id }, onBack = {
                actions.cancelProfileSelection(); onPanel(HomePanel.Profiles)
            }, onSuccess = onClose, onConfirm = { pin, complete -> actions.chooseProfile(panel.profile, pin, complete) })
        }
        else -> Unit
    }
}
