package app.flick.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import app.flick.tv.ui.theme.FlickColors
import app.flick.tv.ui.theme.TvLayout

/** Browsing a form never starts text input. Editing is an explicit, cancellable action. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun NativeTextField(label: String, value: String, onValueChange: (String) -> Unit,
    secret: Boolean = false, numeric: Boolean = false, keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val rowFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var restoreFocus by remember { mutableStateOf(false) }
    fun close() { draft = ""; editing = false; restoreFocus = true }
    LaunchedEffect(editing, restoreFocus, windowFocused) {
        if (!editing && restoreFocus && windowFocused) { rowFocus.requestFocus(); restoreFocus = false }
    }
    TvSettingRow(label,
        detail = if (value.isEmpty()) "Select to enter" else if (secret) "Entered" else value,
        icon = Icons.Default.Edit, modifier = Modifier.focusRequester(rowFocus),
        onClick = { draft = value; editing = true })
    if (editing) TvDialog(label, onDismiss = { close() }) {
        val keyboard = LocalSoftwareKeyboardController.current
        val backPress = remember { RemotePress() }
        DisposableEffect(keyboard) { onDispose { keyboard?.hide() } }
        var focused by remember { mutableStateOf(false) }
        fun save() { keyboard?.hide(); onValueChange(draft); close() }
        val shape = RoundedCornerShape(TvLayout.RowRadius)
        BasicTextField(
            value = draft, onValueChange = { draft = it }, singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when { numeric -> KeyboardType.NumberPassword; secret -> KeyboardType.Password; else -> keyboardType },
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }, onSearch = { save() }),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = FlickColors.Foreground),
            cursorBrush = SolidColor(FlickColors.Foreground),
            modifier = Modifier.semantics { contentDescription = label }.fillMaxWidth()
                .onPreInterceptKeyBeforeSoftKeyboard { event ->
                    if (event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK) false
                    else {
                        if (event.type == KeyEventType.KeyDown) backPress.down(event.nativeKeyEvent.keyCode, event.nativeKeyEvent.repeatCount)
                        if (event.type == KeyEventType.KeyUp && backPress.up(event.nativeKeyEvent.keyCode, event.nativeKeyEvent.isCanceled) == RemotePress.Release.Click) {
                            // Back cancels this editor as one action, including its input session.
                            keyboard?.hide()
                            close()
                        }
                        true
                    }
                }
                .onFocusChanged { focused = it.isFocused }
                .background(FlickColors.Surface, shape)
                .border(if (focused) 2.dp else 1.dp, if (focused) FlickColors.Foreground else FlickColors.Secondary, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        TvSettingRow(if (imeAction == ImeAction.Search) "Search" else "Done", onClick = { save() })
        TvSettingRow("Cancel", onClick = { keyboard?.hide(); close() })
    }
}
