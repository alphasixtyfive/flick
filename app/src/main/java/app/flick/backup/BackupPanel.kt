package app.flick.backup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.*
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.profiles.FourDigitPin
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow

@Composable
fun BackupPanel(state: BackupState, onPrepare: () -> Unit, onSave: () -> Unit, onChoose: () -> Unit,
    onRestore: (String?, (Result<Unit>) -> Unit) -> Unit, onDiscard: () -> Unit, onBack: () -> Unit) {
    var enteringPin by remember(state.preview) { mutableStateOf(false) }
    val back = { if (state.preview != null && !state.busy) onDiscard() else onBack() }
    TvPanel("Backup & restore", onBack = back, focusKey = state.preview to state.busy) {
        if (state.busy) Text("Please wait…", style = MaterialTheme.typography.bodyMedium)
        else if (state.preview == null) {
            Text("Save your profiles, photos and launcher preferences. Passwords, PINs and Home Assistant connections stay on this TV.",
                style = MaterialTheme.typography.bodySmall)
            TvSettingRow("Back up", icon = Icons.Default.FileUpload, onClick = onPrepare)
            TvSettingRow("Restore", icon = Icons.Default.FileDownload, onClick = onChoose)
        } else {
            state.summary?.let { summary ->
                Text("${summary.profileCount} profiles\n${summary.photoCount} wallpapers\n${summary.embeddedImages} saved images",
                    style = MaterialTheme.typography.bodyMedium)
                summary.notices.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.preview == BackupPreview.Export) TvSettingRow("Save backup", icon = Icons.Default.FileUpload, onClick = onSave)
            else {
                Text("This replaces profiles and launcher preferences. Home Assistant and this TV’s connections stay unchanged.",
                    style = MaterialTheme.typography.bodySmall)
                if (state.needsParentPin) Text("Set a new four-digit parent PIN for the restored adult profiles.", style = MaterialTheme.typography.bodySmall)
                TvSettingRow("Restore backup", icon = Icons.Default.Restore,
                    onClick = { if (state.needsParentPin) enteringPin = true else onRestore(null) { } })
            }
            TvSettingRow("Cancel", icon = Icons.Default.Close, onClick = onDiscard)
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (enteringPin) FourDigitPin("New parent PIN", "backup-restore", null,
        onBack = { enteringPin = false }, onSuccess = { enteringPin = false },
        onConfirm = { pin, complete -> onRestore(pin, complete) })
}
