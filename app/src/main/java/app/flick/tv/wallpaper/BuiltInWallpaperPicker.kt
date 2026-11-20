package app.flick.tv.wallpaper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.tv.ui.components.TvFocusSurface
import app.flick.tv.ui.components.TvLazyPanel
import app.flick.tv.ui.components.TvPanel
import app.flick.tv.ui.components.TvSettingRow
import app.flick.tv.ui.theme.FlickColors
import coil.compose.AsyncImage

@Composable
internal fun BuiltInWallpaperPicker(selectedUri: String?, onSelect: (BuiltInWallpaper) -> Unit,
    onBack: () -> Unit, onUseAll: (() -> Unit)? = null) {
    var previewId by remember { mutableStateOf<String?>(null) }
    val preview = BuiltInWallpapers.items.firstOrNull { it.id == previewId }
    if (preview != null) {
        TvPanel(preview.title, onBack = { previewId = null }) {
            AsyncImage(model = preview.drawable, contentDescription = preview.title, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)))
            TvSettingRow("Use wallpaper", icon = Icons.Default.Check, onClick = { onSelect(preview) })
            Text("Included with Flick. Available offline.", style = MaterialTheme.typography.bodySmall)
        }
    } else TvLazyPanel("Built-in wallpapers", onBack = onBack) {
        if (onUseAll != null) item(key = "slideshow") {
            TvSettingRow("Use all as slideshow", icon = Icons.Default.Slideshow, onClick = onUseAll)
        }
        items(BuiltInWallpapers.items, key = { it.id }) { wallpaper ->
            TvFocusSurface(onClick = { previewId = wallpaper.id }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), containerColor = Color.Transparent, focusedScale = 1f) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(model = wallpaper.drawable, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp, 54.dp).clip(RoundedCornerShape(6.dp)).background(FlickColors.SurfaceRaised))
                    Column {
                        Text(wallpaper.title, style = MaterialTheme.typography.bodyMedium)
                        if (wallpaper.uri == selectedUri) Text("Selected", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
