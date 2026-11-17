package app.flick.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.flick.ui.components.TvFocusSurface
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
internal fun ProfileRow(profile: Profile, detail: String, active: Boolean = false, onClick: () -> Unit) {
    TvFocusSurface(
        onClick = onClick,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        focusedScale = 1f,
        shape = RoundedCornerShape(TvLayout.RowRadius),
        modifier = Modifier.fillMaxWidth().semantics { if (active) stateDescription = "Active profile" },
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = TvLayout.RowHeight).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileAvatar(profile)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = .74f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (active) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ProfileAvatar(profile: Profile, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(FlickColors.SurfaceRaised).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(profile.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = FlickColors.Foreground)
        profile.avatar?.let { AvatarImage(it) }
    }
}

private val avatarRetryDelays = listOf(1_000L, 2_000L, 4_000L)

/** Document providers may still be starting when the first profile picker appears. */
@Composable
private fun AvatarImage(uri: String) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var requestVersion by remember(uri) { mutableIntStateOf(0) }
    var retryCount by remember(uri) { mutableIntStateOf(0) }
    var failed by remember(uri) { mutableStateOf(false) }
    val version = requestVersion
    LaunchedEffect(uri, lifecycle, failed, version) {
        if (!failed || retryCount >= avatarRetryDelays.size) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val retryDelay = avatarRetryDelays.getOrNull(retryCount) ?: return@repeatOnLifecycle
            if (!failed || requestVersion != version) return@repeatOnLifecycle
            delay(retryDelay)
            if (failed && requestVersion == version && retryCount < avatarRetryDelays.size) {
                failed = false
                retryCount++
                requestVersion++
            }
        }
    }
    // Coil 2 has no restart API; a new remembered painter retries the unchanged URI.
    key(uri, version) {
        AsyncImage(model = uri, contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onSuccess = {
                if (requestVersion == version) { failed = false; retryCount = 0 }
            },
            onError = { if (requestVersion == version) failed = true },
        )
    }
}
