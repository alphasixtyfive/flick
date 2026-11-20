package app.flick.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.tv.ui.theme.FlickColors
import app.flick.tv.ui.theme.TvLayout
import app.flick.tv.ui.theme.TvOverlayTheme

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun TvDialog(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = 360.dp,
    requestInitialFocus: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TvOverlayTheme {
            Column(
                Modifier.navigationFeedback().width(width).heightIn(max = 460.dp)
                    .background(FlickColors.Background, RoundedCornerShape(TvLayout.DialogRadius))
                    .padding(24.dp).focusRequester(focus)
                    .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                content()
            }
        }
        LaunchedEffect(Unit) { if (requestInitialFocus) focus.requestFocus() }
    }
}
