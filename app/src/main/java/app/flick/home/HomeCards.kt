package app.flick.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import app.flick.apps.AppEntry
import app.flick.apps.ContinueItem
import app.flick.ui.components.TvFocusSurface
import app.flick.ui.theme.TvLayout
import coil.compose.AsyncImage

@Composable
internal fun AppCard(app: AppEntry, compact: Boolean, modifier: Modifier = Modifier, moving: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    TvFocusSurface(onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.width(TvLayout.appTileWidth(compact)).aspectRatio(16f / 9f)) {
        if (app.artwork != null) AsyncImage(model = app.artwork, contentDescription = app.name,
            contentScale = if (app.artworkIsBanner) ContentScale.Crop else ContentScale.Fit,
            modifier = if (app.artworkIsBanner) Modifier.fillMaxSize() else Modifier.size(44.dp).align(Alignment.Center))
        else Text(app.name, modifier = Modifier.align(Alignment.Center).padding(12.dp), style = MaterialTheme.typography.titleMedium)
        if (moving) Icon(Icons.Default.SwapHoriz, "Moving ${app.name}", Modifier.align(Alignment.BottomCenter).padding(6.dp))
    }
}

@Composable
internal fun ContinueCard(item: ContinueItem, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    TvFocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier.size(168.dp, 94.5.dp)) {
        var failed by remember(item.artwork) { mutableStateOf(item.artwork.isNullOrBlank()) }
        if (!failed) AsyncImage(model = item.artwork, contentDescription = item.title,
            onError = { failed = true }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center).padding(12.dp))
        if (item.progress.isFinite() && item.progress > 0f && item.progress < 1f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp).height(2.dp)
                .background(Color.White.copy(alpha = .18f), CircleShape)) {
                Box(Modifier.fillMaxWidth(item.progress).fillMaxHeight()
                    .background(Color.White.copy(alpha = .85f), CircleShape))
            }
        }
    }
}

