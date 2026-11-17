package app.flick.home

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.tv.material3.Text
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout

/** Keeps the message at the screen centre, clear of both measured header groups. */
@Composable
internal fun HomeHeader(
    modifier: Modifier = Modifier,
    message: String?,
    messageStyle: TextStyle,
    clock: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = {
        Box { controls() }
        Box { clock() }
        Box {
            if (!message.isNullOrBlank()) Text(message, style = messageStyle,
                color = FlickColors.Foreground, textAlign = TextAlign.Center,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val clockBlock = measurables[1].measure(loose)
        val controlsBlock = measurables[0].measure(loose.copy(
            maxWidth = (constraints.maxWidth - clockBlock.width - TvLayout.Gap.roundToPx()).coerceAtLeast(0)))
        val width = constraints.maxWidth
        val messageBlock = measurables[2].measure(loose.copy(maxWidth = homeMessageWidth(
            width, controlsBlock.width, clockBlock.width, TvLayout.Gap.roundToPx())))
        val clockCentre = clockBlock[ClockDigitsCentre].takeUnless { it == AlignmentLine.Unspecified }
            ?: clockBlock.height / 2
        val centre = maxOf(controlsBlock.height / 2, clockCentre, messageBlock.height / 2)
        val height = constraints.constrainHeight(maxOf(
            centre + controlsBlock.height - controlsBlock.height / 2,
            centre + clockBlock.height - clockCentre,
            centre + messageBlock.height - messageBlock.height / 2,
        ))
        layout(width, height) {
            controlsBlock.placeRelative(0, centre - controlsBlock.height / 2)
            clockBlock.placeRelative(width - clockBlock.width, centre - clockCentre)
            if (messageBlock.width > 0) messageBlock.placeRelative(
                (width - messageBlock.width) / 2, centre - messageBlock.height / 2)
        }
    }
}

internal fun homeMessageWidth(width: Int, controlsWidth: Int, clockWidth: Int, gap: Int): Int =
    (width - 2 * (maxOf(controlsWidth, clockWidth) + gap)).coerceAtLeast(0)
