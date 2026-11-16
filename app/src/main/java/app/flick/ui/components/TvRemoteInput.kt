package app.flick.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Cancels a physical press when its control loses ownership of input. */
@Composable
internal fun Modifier.tvRemoteInput(
    interactions: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val input = remember { SurfacePress() }
    val latestClick by rememberUpdatedState(onClick)
    val latestLongClick by rememberUpdatedState(onLongClick)
    val scope = rememberCoroutineScope()
    val holdTimeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(input, windowFocused) { if (!windowFocused) input.cancel(interactions) }
    DisposableEffect(input, interactions, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                input.cancel(interactions)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            input.cancel(interactions)
        }
    }
    return this
            .onFocusChanged { if (!it.isFocused) input.cancel(interactions) }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val code = native.keyCode
                if (code !in activationKeys) false
                else {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            val action = latestLongClick
                            if (native.isLongPress) {
                                if (action != null && input.gesture.longPressForKey(code)) {
                                    input.cancel(interactions)
                                    action()
                                }
                            } else {
                                val token = input.gesture.down(code, native.repeatCount)
                                if (token != null) {
                                    input.feedback = PressInteraction.Press(Offset.Zero).also { interactions.tryEmit(it) }
                                    if (latestLongClick != null) input.timeout = scope.launch {
                                        delay(holdTimeout)
                                        input.timeout = null
                                        val heldAction = latestLongClick
                                        if (heldAction != null && input.gesture.longPress(token)) {
                                            // A dialog can take the release without clearing this window's focus.
                                            input.cancel(interactions)
                                            heldAction()
                                        }
                                    }
                                }
                            }
                        }
                        KeyEventType.KeyUp -> {
                            when (input.gesture.up(code, cancelled = native.isCanceled)) {
                                RemotePress.Release.Ignored -> Unit
                                RemotePress.Release.Consumed -> {
                                    input.timeout?.cancel()
                                    input.timeout = null
                                    input.finish(interactions, cancelled = true)
                                }
                                RemotePress.Release.Click -> {
                                    input.timeout?.cancel()
                                    input.timeout = null
                                    input.finish(interactions, cancelled = false)
                                    latestClick()
                                }
                            }
                        }
                    }
                    true
                }
            }
}

/** Visual feedback follows the same cancellation boundary as the gesture and its timeout. */
private class SurfacePress {
    val gesture = RemotePress()
    var feedback: PressInteraction.Press? = null
    var timeout: Job? = null

    fun finish(source: MutableInteractionSource, cancelled: Boolean) {
        feedback?.let { press ->
            source.tryEmit(if (cancelled) PressInteraction.Cancel(press) else PressInteraction.Release(press))
        }
        feedback = null
    }

    fun cancel(source: MutableInteractionSource) {
        timeout?.cancel()
        timeout = null
        gesture.cancel()
        finish(source, cancelled = true)
    }
}

private val activationKeys = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
    android.view.KeyEvent.KEYCODE_ENTER,
    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
    android.view.KeyEvent.KEYCODE_BUTTON_A,
    android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
)
