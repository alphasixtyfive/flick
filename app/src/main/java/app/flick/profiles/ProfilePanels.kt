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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.apps.AppEntry
import app.flick.ui.components.NativeTextField
import app.flick.ui.components.TvDialog
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow

private enum class ProfileEditOperation { Save, Delete }
// Retries must remain distinct even when they report the same error.
private data class ProfileEditCompletion(val sequence: Int, val result: Result<Unit>)

@Composable
internal fun ProfileManagementPanel(profiles: List<Profile>, activeProfileId: String,
    onEdit: (Profile) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    TvPanel("Profiles", onBack = onBack) {
        profiles.forEach { profile -> key(profile.id) {
            val active = profile.id == activeProfileId
            val role = if (profile.child) "Child" else "Adult"
            ProfileRow(profile, detail = if (active) "$role · Active" else role, active = active,
                onClick = { onEdit(profile) })
        } }
        if (profiles.size < 12) TvSettingRow("Add profile", icon = Icons.Default.Add, onClick = onAdd)
        else Text("Up to twelve profiles are supported.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ProfileEditor(profile: Profile, profiles: List<Profile>, apps: List<AppEntry>,
    onSave: (Profile, String, (Result<Unit>) -> Unit) -> Unit,
    onDelete: (Profile, (Result<Unit>) -> Unit) -> Unit,
    onChoosePhoto: (Profile) -> Unit, onRemovePhoto: (Profile) -> Unit, onBack: () -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var child by remember(profile.id) { mutableStateOf(profile.child) }
    var allowed by remember(profile.id) { mutableStateOf(profile.allowedApps.toSet()) }
    var pin by remember(profile.id) { mutableStateOf("") }
    var pending by remember(profile.id) { mutableStateOf<ProfileEditOperation?>(null) }
    var sequence by remember(profile.id) { mutableIntStateOf(0) }
    var completion by remember(profile.id) { mutableStateOf<ProfileEditCompletion?>(null) }
    var localError by remember(profile.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(profile.id) { mutableStateOf(false) }
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
    val otherProfiles = profiles.filterNot { it.id == profile.id }
    val needsPin = !child && otherProfiles.any { it.child } && currentProfile.pinHash == null
    TvPanel(if (existing) "Edit profile" else "Add profile", onBack = onBack) {
        if (existing) {
            ProfileRow(currentProfile.copy(name = name.ifBlank { currentProfile.name }), detail = "Change photo",
                onClick = { if (pending == null) onChoosePhoto(currentProfile) })
            if (currentProfile.avatar != null) TvSettingRow("Remove photo", icon = Icons.Default.ImageNotSupported,
                onClick = { if (pending == null) onRemovePhoto(currentProfile) })
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
            if (needsPin) Text("A parent PIN is required when child profiles are enabled.", style = MaterialTheme.typography.bodySmall)
        }
        TvSettingRow(if (pending == ProfileEditOperation.Save) "Saving…" else "Save profile",
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
                    val draft = currentProfile.copy(name = name.trim(), child = child, allowedApps = allowed.toList())
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
            Text("Allowed apps", style = MaterialTheme.typography.titleMedium)
            Text("Set child accounts and content limits inside each media app too.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = .7f))
            apps.forEach { app ->
                TvSettingRow(app.name, checked = app.packageName in allowed, onClick = select@ {
                    if (pending != null) return@select
                    if (app.packageName in allowed) {
                        allowed = allowed - app.packageName
                        localError = null
                    } else if (allowed.size < 100) {
                        allowed = allowed + app.packageName
                        localError = null
                    } else localError = "Choose up to one hundred allowed apps."
                })
            }
        }
        if (existing && profiles.size > 1 && (currentProfile.child || profiles.count { !it.child } > 1)) {
            TvSettingRow(if (pending == ProfileEditOperation.Delete) "Deleting…" else "Delete profile", icon = Icons.Default.Delete,
                onClick = { if (pending == null) confirmDelete = true })
        }
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
