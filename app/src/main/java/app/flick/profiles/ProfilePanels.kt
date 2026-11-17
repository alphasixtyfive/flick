package app.flick.profiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.apps.AppEntry
import app.flick.R
import app.flick.wallpaper.BuiltInWallpaperPicker
import app.flick.ui.components.NativeTextField
import app.flick.ui.components.TvDialog
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.components.TvChoiceRow

private enum class ProfileEditOperation { Save, Delete }
// Retries must remain distinct even when they report the same error.
private data class ProfileEditCompletion(val sequence: Int, val result: Result<Unit>)

@Composable
internal fun ProfileManagementPanel(profiles: List<Profile>, activeProfileId: String,
    onEdit: (Profile) -> Unit, onAdd: () -> Unit, onBack: () -> Unit,
    startup: ProfileStartup, onStartup: () -> Unit) {
    TvPanel("Profiles", onBack = onBack) {
        profiles.forEach { profile -> key(profile.id) {
            val active = profile.id == activeProfileId
            val role = if (profile.child) "Child" else "Adult"
            ProfileRow(profile, detail = role, active = active,
                onClick = { onEdit(profile) })
        } }
        if (profiles.size < 12) TvSettingRow("Add profile", icon = Icons.Default.Add, onClick = onAdd)
        else Text("Up to twelve profiles are supported.", style = MaterialTheme.typography.bodySmall)
        TvSettingRow("On startup & wake", detail = startup.label(), icon = Icons.Default.PowerSettingsNew,
            onClick = onStartup)
        Text("Ask requires a PIN for each adult. Other apps stay in control while they are open.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ProfileStartupPanel(startup: ProfileStartup, onStartupChange: (ProfileStartup) -> Unit,
    standbySource: String?, homeAssistantConfigured: Boolean, onStandby: () -> Unit, onBack: () -> Unit) {
    TvPanel("Startup & wake", onBack = onBack) {
        ProfileStartup.entries.forEach { choice ->
            TvChoiceRow(choice.label(), selected = startup == choice,
                onClick = { if (choice != startup) onStartupChange(choice) })
        }
        Text("Ask requires a PIN for each adult. Returning from another app keeps the current profile.",
            style = MaterialTheme.typography.bodySmall)
        if (homeAssistantConfigured) {
            TvSettingRow("TV standby detection", detail = standbySource ?: "Android screen-off",
                icon = Icons.Default.PowerSettingsNew, onClick = onStandby)
            Text("Optional: use your TV’s Home Assistant state only if the Android box stays awake when the screen turns off.",
                style = MaterialTheme.typography.bodySmall)
        } else Text("Android screen-off detection is used. Connect Home Assistant if your box stays awake when the television turns off.",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun ProfileStartup.label() = when (this) {
    ProfileStartup.Ask -> "Ask who’s watching"
    ProfileStartup.ResumeLast -> "Resume last profile"
}

@Composable
internal fun ProfileEditor(profile: Profile, profiles: List<Profile>, apps: List<AppEntry>, startup: ProfileStartup,
    onSave: (Profile, String, (Result<Unit>) -> Unit) -> Unit,
    onDelete: (Profile, (Result<Unit>) -> Unit) -> Unit,
    onSetApp: (Profile, String, Boolean, (Result<Unit>) -> Unit) -> Unit,
    onChoosePhoto: (Profile) -> Unit, onRemovePhoto: (Profile) -> Unit,
    onChooseWallpaper: (Profile) -> Unit, onRemoveWallpaper: (Profile) -> Unit,
    onSetWallpaper: (Profile, String) -> Unit, onBack: () -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var child by remember(profile.id) { mutableStateOf(profile.child) }
    var pin by remember(profile.id) { mutableStateOf("") }
    var pending by remember(profile.id) { mutableStateOf<ProfileEditOperation?>(null) }
    var sequence by remember(profile.id) { mutableIntStateOf(0) }
    var completion by remember(profile.id) { mutableStateOf<ProfileEditCompletion?>(null) }
    var localError by remember(profile.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(profile.id) { mutableStateOf(false) }
    var choosingWallpaper by remember(profile.id) { mutableStateOf(false) }
    var builtInWallpaper by remember(profile.id) { mutableStateOf(false) }
    var editingApps by remember(profile.id) { mutableStateOf(false) }
    val currentProfile = profiles.find { it.id == profile.id } ?: profile
    // A result received after Back only updates detached state; it cannot navigate.
    LaunchedEffect(completion) {
        val finished = completion ?: return@LaunchedEffect
        pending = null
        finished.result.fold(
            onSuccess = { onBack() },
            onFailure = { localError = it.message?.take(160) ?: "Changes could not be saved." },
        )
    }
    fun perform(operation: ProfileEditOperation, action: ((Result<Unit>) -> Unit) -> Unit) {
        if (pending != null) return
        pending = operation
        localError = null
        sequence++
        val submittedSequence = sequence
        action { result -> completion = ProfileEditCompletion(submittedSequence, result) }
    }
    val existing = profiles.any { it.id == profile.id }
    val changed = !existing || name.trim() != currentProfile.name || child != currentProfile.child || pin.isNotEmpty()
    val otherProfiles = profiles.filterNot { it.id == profile.id }
    val needsPin = !child && (startup == ProfileStartup.Ask || otherProfiles.any { it.child }) && currentProfile.pinHash == null
    if (editingApps && existing && currentProfile.child) {
        ProfileAppAccessPanel(currentProfile, apps, onSetApp, onBack = { editingApps = false })
        return
    }
    if (builtInWallpaper) {
        BuiltInWallpaperPicker(currentProfile.wallpaperUri, onSelect = {
            onSetWallpaper(currentProfile, it.uri)
            builtInWallpaper = false
        }, onBack = { builtInWallpaper = false })
        return
    }
    TvPanel(if (existing) "Edit profile" else "Add profile", onBack = onBack) {
        if (existing) {
            ProfileRow(currentProfile.copy(name = name.ifBlank { currentProfile.name }), detail = "Change photo",
                onClick = { if (pending == null) onChoosePhoto(currentProfile) })
            if (currentProfile.avatar != null) TvSettingRow("Remove photo", icon = Icons.Default.ImageNotSupported,
                onClick = { if (pending == null) onRemovePhoto(currentProfile) })
            TvSettingRow("Profile wallpaper", icon = Icons.Default.Wallpaper,
                detail = if (currentProfile.wallpaperUri == null) "Using shared wallpapers" else "Custom wallpaper",
                onClick = { if (pending == null) choosingWallpaper = true })
            if (currentProfile.wallpaperUri != null) TvSettingRow("Use shared wallpapers", icon = Icons.Default.PhotoLibrary,
                onClick = { if (pending == null) onRemoveWallpaper(currentProfile) })
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileAvatar(profile.copy(name = name.ifBlank { "?" }), size = 40.dp)
            Text("Choose a photo after saving.", style = MaterialTheme.typography.bodySmall)
        }
        NativeTextField("Name", name, { if (pending == null) { name = it.take(48); localError = null } })
        TvSettingRow("Child profile", checked = child, icon = Icons.Default.ChildCare,
            onClick = { if (pending == null) { child = !child; pin = ""; localError = null } })
        if (!child) {
            val pinLabel = when {
                currentProfile.pinHash != null -> "Change parent PIN"
                needsPin -> "Parent PIN"
                else -> "Parent PIN (optional)"
            }
            NativeTextField(pinLabel, pin, {
                if (pending == null && it.length <= 4 && it.all { digit -> digit in '0'..'9' }) { pin = it; localError = null }
            }, secret = true, numeric = true)
            if (needsPin) Text(if (startup == ProfileStartup.Ask) "Ask who’s watching requires a PIN for every adult."
                else "A parent PIN is required when child profiles are enabled.", style = MaterialTheme.typography.bodySmall)
        }
        if (changed || pending == ProfileEditOperation.Save) TvSettingRow(if (pending == ProfileEditOperation.Save) "Saving…" else "Save profile",
            icon = Icons.Default.Check, onClick = save@ {
                if (pending != null) return@save
                localError = when {
                    name.trim().isEmpty() -> "Enter a name."
                    name.any(Char::isISOControl) -> "Use a name without control characters."
                    pin.isNotEmpty() && pin.length != 4 -> "Use exactly four digits for a PIN."
                    needsPin && pin.isEmpty() -> "Set a parent PIN before saving."
                    child && otherProfiles.none { !it.child } -> "Keep at least one adult profile."
                    child && otherProfiles.any { !it.child && it.pinHash == null } -> "Set a PIN for every adult before adding a child profile."
                    else -> null
                }
                if (localError == null) {
                    val draft = currentProfile.copy(name = name.trim(), child = child)
                    if (draft == currentProfile && pin.isEmpty()) onBack()
                    else {
                        val submittedPin = pin
                        pin = ""
                        perform(ProfileEditOperation.Save) { done -> onSave(draft, submittedPin, done) }
                    }
                }
            })
        localError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xffffd5cd)) }
        if (child) {
            Text("Set child accounts and content limits inside each media app too.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .7f))
            if (existing && currentProfile.child) TvSettingRow("Allowed apps", icon = Icons.Default.Apps,
                detail = pluralStringResource(R.plurals.allowed_apps_count, currentProfile.allowedApps.size, currentProfile.allowedApps.size),
                onClick = { if (pending == null) editingApps = true })
            else Text("Save this profile before choosing allowed apps.", style = MaterialTheme.typography.bodySmall)
        }
        if (existing && profiles.size > 1 && (currentProfile.child || profiles.count { !it.child } > 1)) {
            TvSettingRow(if (pending == ProfileEditOperation.Delete) "Deleting…" else "Delete profile", icon = Icons.Default.Delete,
                onClick = { if (pending == null) confirmDelete = true })
        }
    }
    if (choosingWallpaper) TvDialog("Profile wallpaper", onDismiss = { choosingWallpaper = false }) {
        TvSettingRow("Built-in wallpapers", icon = Icons.Default.Wallpaper, onClick = {
            choosingWallpaper = false
            builtInWallpaper = true
        })
        TvSettingRow("Choose photo", icon = Icons.Default.PhotoLibrary, onClick = {
            choosingWallpaper = false
            onChooseWallpaper(currentProfile)
        })
    }
    if (confirmDelete) TvDialog("Delete ${currentProfile.name}?", onDismiss = { confirmDelete = false }) {
        Text("This profile and its preferences will be removed.", style = MaterialTheme.typography.bodyMedium)
        TvSettingRow("Cancel", onClick = { confirmDelete = false })
        TvSettingRow("Delete profile", icon = Icons.Default.Delete, onClick = {
            confirmDelete = false
            perform(ProfileEditOperation.Delete) { done -> onDelete(currentProfile, done) }
        })
    }
}
