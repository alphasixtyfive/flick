package app.flick.updates

import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.components.TvStatusRow

@Composable
fun UpdatePanel(state: UpdateState, onCheck: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit,
    onCancel: () -> Unit, onClose: () -> Unit) {
    val downloadSize = state.release?.let { Formatter.formatShortFileSize(LocalContext.current, it.size) }
    TvPanel("Updates", onBack = onClose, focusKey = state.phase) {
        Text("Installed version ${state.currentVersion}", style = MaterialTheme.typography.bodyMedium)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when (state.phase) {
            UpdatePhase.Idle, UpdatePhase.Current -> {
                if (state.phase == UpdatePhase.Current) Text("Flick is up to date.", style = MaterialTheme.typography.bodyMedium)
                TvSettingRow("Check for updates", detail = "Stable releases from GitHub", icon = Icons.Default.Refresh, onClick = onCheck)
            }
            UpdatePhase.Checking -> {
                TvStatusRow("Checking for updates…", detail = "GitHub releases", icon = Icons.Default.Refresh)
                TvSettingRow("Cancel", icon = Icons.Default.Close, onClick = onCancel)
            }
            UpdatePhase.Available -> {
                state.release?.let { TvSettingRow("Download update", detail = "Version ${it.version}\n$downloadSize",
                    icon = Icons.Default.Download, onClick = onDownload) }
                TvSettingRow("Check again", icon = Icons.Default.Refresh, onClick = onCheck)
            }
            UpdatePhase.Downloading -> {
                TvStatusRow("Downloading update", detail = "${state.progress}%", icon = Icons.Default.Download)
                TvSettingRow("Cancel download", icon = Icons.Default.Close, onClick = onCancel)
            }
            UpdatePhase.Verifying -> {
                TvStatusRow("Verifying update…", detail = "Checking package and signing key", icon = Icons.Default.SystemUpdate)
                TvSettingRow("Cancel", icon = Icons.Default.Close, onClick = onCancel)
            }
            UpdatePhase.Ready -> TvSettingRow("Install update", detail = state.release?.let { "Version ${it.version}" }.orEmpty(),
                icon = Icons.Default.SystemUpdate, onClick = onInstall)
            UpdatePhase.Installing -> {
                TvStatusRow("Android installer", detail = "Confirm or cancel the update on the Android screen.",
                    icon = Icons.Default.SystemUpdate)
                TvSettingRow("Cancel installation", icon = Icons.Default.Close, onClick = onCancel)
            }
        }
    }
}
