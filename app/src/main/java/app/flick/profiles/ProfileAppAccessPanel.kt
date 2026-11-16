package app.flick.profiles

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.apps.AppEntry
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.theme.FlickColors

/** Switches reflect committed permissions; failed writes leave the saved state visible. */
@Composable
internal fun ProfileAppAccessPanel(
    profile: Profile,
    apps: List<AppEntry>,
    onSetApp: (Profile, String, Boolean, (Result<Unit>) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var saving by remember(profile.id) { mutableStateOf(emptySet<String>()) }
    var error by remember(profile.id) { mutableStateOf<String?>(null) }
    TvPanel("Allowed apps", onBack = onBack) {
        Text(error ?: if (saving.isEmpty()) "Changes save automatically." else "Saving changes…",
            style = MaterialTheme.typography.bodySmall,
            color = if (error == null) FlickColors.Secondary else Color(0xffffd5cd))
        apps.forEach { app -> key(app.packageName) {
            TvSettingRow(app.name, checked = app.packageName in profile.allowedApps, onClick = {
                if (app.packageName !in saving) {
                    saving = saving + app.packageName
                    error = null
                    onSetApp(profile, app.packageName, app.packageName !in profile.allowedApps) { result ->
                        saving = saving - app.packageName
                        error = result.exceptionOrNull()?.message?.take(160)
                    }
                }
            })
        } }
        if (apps.isEmpty()) Text("No TV apps are installed.", style = MaterialTheme.typography.bodySmall)
    }
}
