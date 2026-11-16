package app.flick.profiles

import android.view.KeyEvent
import android.view.InputDevice
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.flick.ui.components.RemotePress
import app.flick.ui.components.TvDialog
import app.flick.ui.components.TvFocusSurface
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout

private data class PinCompletion(val attempt: Int, val result: Result<Unit>)

@Composable
internal fun ProfilePin(profile: Profile, error: String?, onBack: () -> Unit, onSuccess: () -> Unit,
    onConfirm: (String, (Result<Unit>) -> Unit) -> Unit) {
    var input by remember(profile.id) { mutableStateOf(ProfilePinInput()) }
    var inputError by remember(profile.id) { mutableStateOf(error) }
    var pending by remember(profile.id) { mutableStateOf(false) }
    var attempt by remember(profile.id) { mutableIntStateOf(0) }
    var completion by remember(profile.id) { mutableStateOf<PinCompletion?>(null) }
    val numberPress = remember(profile.id) { RemotePress() }
    val initialFocus = remember(profile.id) { FocusRequester() }

    fun delete() {
        if (!pending) { input = input.delete(); inputError = null }
    }
    fun submit() {
        if (pending) return
        if (!input.complete) { inputError = "Enter four digits."; return }
        val submitted = input.forVerification()
        inputError = null
        pending = true
        numberPress.cancel()
        val submittedAttempt = ++attempt
        onConfirm(submitted) { result -> completion = PinCompletion(submittedAttempt, result) }
    }
    fun digit(value: Char) {
        if (!pending) {
            input = input.digit(value)
            inputError = null
            if (input.complete) submit()
        }
    }

    TvDialog("${profile.name}’s PIN", onDismiss = onBack, width = 300.dp, requestInitialFocus = false) {
        // This owner belongs to the dialog window, so an Activity focus change cannot revive a held key.
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(numberPress, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    numberPress.cancel()
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer); numberPress.cancel() }
        }
        LaunchedEffect(profile.id, windowFocused) {
            if (windowFocused) initialFocus.requestFocus() else numberPress.cancel()
        }
        LaunchedEffect(completion) {
            val finished = completion ?: return@LaunchedEffect
            if (finished.attempt != attempt) return@LaunchedEffect
            finished.result.fold(onSuccess = { onSuccess() }, onFailure = {
                pending = false
                input = ProfilePinInput()
                inputError = it.message?.take(160) ?: "PIN could not be checked. Try again."
                if (windowFocused) initialFocus.requestFocus()
            })
        }
        Column(Modifier.fillMaxWidth()
            .onFocusChanged { if (!it.hasFocus) numberPress.cancel() }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val code = native.keyCode
                val number = when (code) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (code - KeyEvent.KEYCODE_0)
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (code - KeyEvent.KEYCODE_NUMPAD_0)
                    else -> null
                }
                val erase = code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL
                val keyboardSubmit = (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                    native.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                if (number == null && !erase && !keyboardSubmit) false
                else {
                    when (event.type) {
                        KeyEventType.KeyDown -> if (!pending) numberPress.down(code, native.repeatCount)
                        KeyEventType.KeyUp -> if (numberPress.up(code, native.isCanceled) == RemotePress.Release.Click && !pending) {
                            when { number != null -> digit(number); erase -> delete(); else -> submit() }
                        }
                    }
                    true
                }
            }.focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {
                password()
                contentDescription = if (pending) "Checking PIN" else "${input.length} of 4 digits entered"
            }, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                repeat(4) { index ->
                    val next = !pending && index == input.length
                    Box(Modifier.size(42.dp, 48.dp).border(if (next) 2.dp else 1.dp,
                        if (next || index < input.length) FlickColors.Foreground else FlickColors.Secondary,
                        RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        if (index < input.length) Box(Modifier.size(10.dp).background(FlickColors.Foreground, CircleShape))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                (if (pending) "Checking…" else inputError)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary, maxLines = 2)
                }
            }
            (1..9).chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { number ->
                        PinKey(number.toString(), Modifier.weight(1f).then(if (number == 1) Modifier.focusRequester(initialFocus) else Modifier),
                            onClick = { digit('0' + number) }) { Text(number.toString(), style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PinKey("Delete digit", Modifier.weight(1f), onClick = ::delete) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                PinKey("0", Modifier.weight(1f), onClick = { digit('0') }) { Text("0", style = MaterialTheme.typography.titleMedium) }
                PinKey("Back", Modifier.weight(1f), onClick = onBack) { Text("Back", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun PinKey(label: String, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    TvFocusSurface(onClick = onClick, modifier = modifier.height(48.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(TvLayout.RowRadius), focusedScale = 1f) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
