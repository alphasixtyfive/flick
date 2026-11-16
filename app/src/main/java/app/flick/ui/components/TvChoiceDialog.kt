package app.flick.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

@Composable
internal fun <T> TvChoiceDialog(title: String, values: List<T>, current: T,
    label: (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    val initial = values.firstOrNull { it == current } ?: values.firstOrNull()
    TvDialog(title, onDismiss, requestInitialFocus = false) {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            values.forEach { value ->
                TvChoiceRow(label(value), selected = value == current,
                    modifier = if (value == initial) Modifier.focusRequester(focus) else Modifier,
                    onClick = { onSelect(value); onDismiss() })
            }
        }
        LaunchedEffect(initial) { if (initial != null) focus.requestFocus() }
    }
}
