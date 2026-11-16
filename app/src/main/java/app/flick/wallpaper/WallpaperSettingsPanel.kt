package app.flick.wallpaper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.components.NativeTextField
import app.flick.ui.components.TvFocusSurface
import app.flick.ui.components.TvLazyPanel
import app.flick.ui.components.TvDialog
import app.flick.ui.components.TvChoiceDialog
import app.flick.ui.components.TvPanel
import app.flick.ui.components.TvSettingRow
import app.flick.ui.theme.FlickColors
import coil.compose.AsyncImage

private enum class SlideshowChoice { Interval, Dimming }

private sealed interface WallpaperPage {
    data object Sources : WallpaperPage
    data object Slideshow : WallpaperPage
    data object Advanced : WallpaperPage
    data object Photos : WallpaperPage
    data object AddPhoto : WallpaperPage
    data class Photo(val uri: String) : WallpaperPage
}

@Composable
internal fun WallpaperSettingsPanel(settings: WallpaperSettings, onSave: (WallpaperSettings) -> Unit,
    onNext: () -> Unit, onChooseFolder: () -> Unit, onChoosePhotos: () -> Unit, onBack: () -> Unit) {
    var page by remember { mutableStateOf<WallpaperPage>(WallpaperPage.Sources) }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingPhoto by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf<SlideshowChoice?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(settings.photos, pendingPhoto) {
        val pending = pendingPhoto ?: return@LaunchedEffect
        if (settings.photos.any { it.uri == pending }) {
            pendingPhoto = null
            url = ""
            error = null
            page = WallpaperPage.Photos
        }
    }
    when (val current = page) {
        WallpaperPage.Sources -> TvPanel("Wallpapers", onBack = onBack) {
            TvSettingRow("Choose photos", detail = "Select several pictures at once", icon = Icons.Default.PhotoLibrary,
                onClick = onChoosePhotos)
            TvSettingRow("Photo folder", detail = if (settings.folderUri == null) "Use a folder as a slideshow" else "Folder selected",
                icon = Icons.Default.FolderOpen, onClick = onChooseFolder)
            if (settings.photos.isNotEmpty()) TvSettingRow("Selected photos", detail = "${settings.photos.size} selected", icon = Icons.Default.Collections,
                onClick = { page = WallpaperPage.Photos })
            TvSettingRow("Slideshow", detail = photoIntervalLabel(settings.intervalSeconds), icon = Icons.Default.Slideshow,
                onClick = { page = WallpaperPage.Slideshow })
            TvSettingRow("Advanced", detail = "Online photo links", icon = Icons.Default.Tune,
                onClick = { page = WallpaperPage.Advanced })
        }
        WallpaperPage.Slideshow -> TvPanel("Slideshow", onBack = { page = WallpaperPage.Sources }) {
            Text("Selected photos and the photo folder rotate together.", style = MaterialTheme.typography.bodySmall)
            TvSettingRow("Next photo", icon = Icons.Default.PhotoLibrary, onClick = onNext)
            TvSettingRow("Shuffle", checked = settings.shuffle, icon = Icons.Default.Shuffle,
                onClick = { onSave(settings.copy(shuffle = !settings.shuffle)) })
            TvSettingRow("Change photo", detail = photoIntervalLabel(settings.intervalSeconds), icon = Icons.Default.Timer,
                onClick = { choice = SlideshowChoice.Interval })
            TvSettingRow("Dim photos", detail = "${(settings.dimAmount * 100).toInt()}%", icon = Icons.Default.BrightnessMedium,
                onClick = { choice = SlideshowChoice.Dimming })
        }
        WallpaperPage.Advanced -> TvPanel("Advanced", onBack = { page = WallpaperPage.Sources }) {
            TvSettingRow("Add photo URL", detail = "A direct image link", icon = Icons.Default.Link, onClick = {
                error = null
                pendingPhoto = null
                page = WallpaperPage.AddPhoto
            })
            Text("For a collection, choose photos or a folder on the Wallpapers screen. No web server is needed.", style = MaterialTheme.typography.bodySmall)
            if (settings.folderUri != null || settings.photos.isNotEmpty()) TvSettingRow("Clear photo selection", icon = Icons.Default.Remove,
                onClick = { confirmClear = true })
        }
        WallpaperPage.Photos -> TvLazyPanel("Selected photos", onBack = { page = WallpaperPage.Sources }) {
            item(key = "choose") {
                TvSettingRow("Choose more photos", icon = Icons.Default.AddPhotoAlternate, onClick = onChoosePhotos)
            }
            if (settings.photos.isEmpty()) item(key = "empty") {
                Text("No individual photos selected. Your photo folder is configured separately.", style = MaterialTheme.typography.bodyMedium)
            }
            itemsIndexed(settings.photos.take(MAX_CONFIGURED_PHOTOS), key = { _, photo -> photo.uri }) { index, photo ->
                TvFocusSurface(onClick = { page = WallpaperPage.Photo(photo.uri) }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp), containerColor = Color.Transparent, focusedScale = 1f) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(model = photo.uri, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(FlickColors.SurfaceRaised))
                        val label = photoSourceLabel(photo.uri).takeUnless { it == "Selected photo" } ?: "Photo ${index + 1}"
                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        WallpaperPage.AddPhoto -> TvPanel("Add photo URL", onBack = { page = WallpaperPage.Advanced }) {
            NativeTextField("Photo URL", url, { url = it.take(4096); error = null }, keyboardType = KeyboardType.Uri)
            Text("Use a direct HTTP or HTTPS image address. The link is stored privately on this TV.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            TvSettingRow(if (pendingPhoto != null) "Saving…" else "Add photo", icon = Icons.Default.Add, onClick = {
                val candidate = url.trim()
                error = when {
                    pendingPhoto != null -> "Wait for the current photo to finish saving."
                    !validOnlinePhotoUrl(candidate) -> "Enter an HTTP or HTTPS photo URL without a login."
                    settings.photos.any { it.uri == candidate } -> "This photo is already selected."
                    settings.photos.size >= MAX_CONFIGURED_PHOTOS -> "Choose up to $MAX_CONFIGURED_PHOTOS photos."
                    else -> null
                }
                if (error == null) {
                    pendingPhoto = candidate
                    onSave(settings.copy(photos = settings.photos + WallpaperPhoto(candidate)))
                }
            })
            if (pendingPhoto != null) TvSettingRow("Try again", detail = "If saving failed", icon = Icons.Default.Refresh,
                onClick = { pendingPhoto = null })
        }
        is WallpaperPage.Photo -> TvPanel("Photo", onBack = { page = WallpaperPage.Photos }) {
            var previewFailed by remember(current.uri) { mutableStateOf(false) }
            Text(photoSourceLabel(current.uri), style = MaterialTheme.typography.bodyMedium)
            if (previewFailed) Text("This photo could not be loaded.", style = MaterialTheme.typography.bodyMedium)
            else AsyncImage(model = current.uri, contentDescription = "Photo preview", contentScale = ContentScale.Fit,
                onError = { previewFailed = true }, modifier = Modifier.fillMaxWidth().height(150.dp))
            TvSettingRow("Remove photo", icon = Icons.Default.Remove, onClick = {
                onSave(settings.copy(photos = settings.photos.filterNot { it.uri == current.uri }))
                page = WallpaperPage.Photos
            })
        }
    }
    when (choice) {
        SlideshowChoice.Interval -> TvChoiceDialog("Change photo", listOf(30, 60, 300, 900, 1800, 3600, 86400).plus(settings.intervalSeconds).distinct().sorted(),
            settings.intervalSeconds, ::photoIntervalLabel, { onSave(settings.copy(intervalSeconds = it)) }, { choice = null })
        SlideshowChoice.Dimming -> TvChoiceDialog("Dim photos", listOf(0f, .1f, .2f, .35f, .5f, .7f).plus(settings.dimAmount).distinct().sorted(),
            settings.dimAmount, { "${(it * 100).toInt()}%" }, { onSave(settings.copy(dimAmount = it)) }, { choice = null })
        null -> Unit
    }
    if (confirmClear) TvDialog("Clear photo selection?", onDismiss = { confirmClear = false }) {
        Text("Remove the selected photos and folder from Flick. The original files stay where they are.", style = MaterialTheme.typography.bodyMedium)
        TvSettingRow("Cancel", onClick = { confirmClear = false })
        TvSettingRow("Clear selection", icon = Icons.Default.Remove, onClick = {
            onSave(settings.copy(folderUri = null, photos = emptyList()))
            confirmClear = false
        })
    }
}
