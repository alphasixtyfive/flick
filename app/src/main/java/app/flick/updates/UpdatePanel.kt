package app.flick.updates

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.theme.TvLayout

@Composable
fun UpdatePanel(state: UpdateState, onCheck: () -> Unit, onDownload: () -> Unit, onInstall: () -> Unit,
    onCancel: () -> Unit, onClose: () -> Unit) {
    val downloadSize = state.release?.let { Formatter.formatShortFileSize(LocalContext.current, it.size) }
    LaunchedEffect(Unit) { if (state.phase == UpdatePhase.Idle) onCheck() }
    val status = state.message ?: when (state.phase) {
        UpdatePhase.Idle -> null
        UpdatePhase.Checking -> "Checking for updates…"
        UpdatePhase.Current -> "Flick is up to date."
        UpdatePhase.Available -> state.release?.let { "Version ${it.version} is available." }
        UpdatePhase.Downloading -> "Downloading… ${state.progress}%"
        UpdatePhase.Verifying -> "Verifying update…"
        UpdatePhase.Ready -> state.release?.let { "Version ${it.version} is ready to install." }
        UpdatePhase.Installing -> "Confirm the update in Android’s installer."
    }
    TvPanel("Updates", onBack = onClose, focusKey = state.phase) {
        Column(Modifier.padding(start = TvLayout.RowTextInset, end = TvLayout.RowHorizontalPadding)) {
            Text("Installed ${state.currentVersion}", style = MaterialTheme.typography.bodySmall)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        when (state.phase) {
            UpdatePhase.Idle, UpdatePhase.Current -> TvSettingRow("Check again", icon = Icons.Default.Refresh, onClick = onCheck)
            UpdatePhase.Checking, UpdatePhase.Verifying -> {
                TvSettingRow("Cancel", icon = Icons.Default.Close, onClick = onCancel)
            }
            UpdatePhase.Available -> {
                state.release?.let { TvSettingRow("Download update", detail = downloadSize.orEmpty(),
                    icon = Icons.Default.Download, onClick = onDownload) }
            }
            UpdatePhase.Downloading -> {
                TvSettingRow("Cancel download", icon = Icons.Default.Close, onClick = onCancel)
            }
            UpdatePhase.Ready -> TvSettingRow("Install update",
                icon = Icons.Default.SystemUpdate, onClick = onInstall)
            UpdatePhase.Installing -> {
                TvSettingRow("Cancel installation", icon = Icons.Default.Close, onClick = onCancel)
            }
        }
    }
}
