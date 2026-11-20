package app.flick.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import app.flick.tv.platform.isNavigationFeedback

internal val LocalNavigationFeedback = staticCompositionLocalOf<() -> Unit> { {} }

/** Install once per Compose window; focus restoration and nested panels stay silent. */
@Composable
internal fun Modifier.navigationFeedback(): Modifier {
    val play = LocalNavigationFeedback.current
    return onPreviewKeyEvent { event ->
        val key = event.nativeKeyEvent
        if (isNavigationFeedback(key.action, key.keyCode, key.repeatCount, key.isLongPress)) play()
        false
    }
}

