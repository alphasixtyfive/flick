package app.flick.profiles

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.apps.AppEntry
import app.flick.ui.components.TvLazyPanel
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
    val installed = remember(apps) { apps.mapTo(mutableSetOf()) { it.packageName } }
    val uninstalled = profile.allowedApps.filterNot(installed::contains)
    fun toggleApp(packageName: String) {
        if (packageName in saving) return
        saving = saving + packageName
        error = null
        onSetApp(profile, packageName, packageName !in profile.allowedApps) { result ->
            saving = saving - packageName
            error = result.exceptionOrNull()?.message?.take(160)
        }
    }
    TvLazyPanel("Allowed apps", onBack = onBack, focusKey = apps.isNotEmpty() || uninstalled.isNotEmpty()) {
        item(key = "status") {
            Text(error ?: if (saving.isEmpty()) "Changes save automatically." else "Saving changes…",
                style = MaterialTheme.typography.bodySmall,
                color = if (error == null) FlickColors.Secondary else Color(0xffffd5cd))
        }
        items(apps, key = { "app:${it.packageName}" }) { app ->
            TvSettingRow(app.name, checked = app.packageName in profile.allowedApps,
                onClick = { toggleApp(app.packageName) })
        }
        items(uninstalled, key = { "app:$it" }) { packageName ->
            TvSettingRow(packageName, detail = "Not installed", checked = true,
                onClick = { toggleApp(packageName) })
        }
        if (apps.isEmpty()) item(key = "empty") {
            Text("No TV apps are installed.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
