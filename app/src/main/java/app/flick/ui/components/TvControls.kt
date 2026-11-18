package app.flick.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout

/** Material owns focus and accessibility; remote gestures finish on the control that began them. */
@Composable
fun TvFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(TvLayout.CardRadius),
    interactionSource: MutableInteractionSource? = null,
    containerColor: Color = FlickColors.Surface,
    focusedScale: Float = 1.04f,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    Surface(
        // These remain available to accessibility actions. Hardware activation is handled below.
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = if (enabled) modifier.tvRemoteInput(interactions, onClick, onLongClick) else modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = FlickColors.Foreground,
            disabledContainerColor = containerColor,
            disabledContentColor = FlickColors.Foreground,
            focusedContainerColor = FlickColors.FocusBackground,
            focusedContentColor = FlickColors.FocusForeground,
            pressedContainerColor = FlickColors.FocusBackground,
            pressedContentColor = FlickColors.FocusForeground,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = if (focusedScale > 1f) 1.01f else 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, FlickColors.FocusBackground), shape = shape),
        ),
        interactionSource = interactions,
        content = content,
    )
}

/** Visible icon only; the name remains available to accessibility services. */
@Composable
fun TvAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TvFocusSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.size(TvLayout.TopActionSize).semantics { contentDescription = label },
        shape = CircleShape,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.align(Alignment.Center).size(18.dp))
    }
}
