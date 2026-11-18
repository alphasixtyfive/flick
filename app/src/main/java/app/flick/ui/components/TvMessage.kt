package app.flick.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.theme.FlickColors
import kotlinx.coroutines.delay

@Composable
internal fun BoxScope.TvMessage(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(6_000)
        onDismiss()
    }
    Text(message, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.align(Alignment.BottomCenter).padding(22.dp)
            .background(FlickColors.SurfaceRaised.copy(alpha = .94f)).padding(horizontal = 18.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite })
}
