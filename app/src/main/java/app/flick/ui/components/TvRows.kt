package app.flick.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout

@Composable
fun TvSettingRow(title: String, detail: String = "", checked: Boolean? = null, icon: ImageVector? = null,
    modifier: Modifier = Modifier, busy: Boolean = false, onClick: () -> Unit) {
    TvListRow(title, detail, icon, modifier.semantics {
        if (checked != null) {
            role = Role.Switch
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        }
    }, onClick) { focused -> if (checked != null) NeutralSwitch(checked, focused, busy) }
}

/** An exclusive choice has selection semantics rather than an on/off switch. */
@Composable
fun TvChoiceRow(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvListRow(title, "", null, modifier.semantics { this.selected = selected; role = Role.RadioButton }, onClick) {
        Icon(if (selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
            contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** Remote focus keeps long status lists scrollable without advertising a click action. */
@Composable
fun TvStatusRow(title: String, detail: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvLayout.RowRadius)
    Box(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
        .background(if (focused) FlickColors.FocusBackground else Color.Transparent, shape)
        .then(if (focused) Modifier.border(2.dp, FlickColors.FocusBackground, shape) else Modifier)
        .focusable(interactionSource = interactions)) {
        CompositionLocalProvider(LocalContentColor provides if (focused) FlickColors.FocusForeground else FlickColors.Foreground) {
            TvRowContent(title, detail, icon) {}
        }
    }
}

@Composable
private fun TvListRow(title: String, detail: String, icon: ImageVector?, modifier: Modifier,
    onClick: () -> Unit, trailing: @Composable RowScope.(Boolean) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    TvFocusSurface(onClick, modifier.fillMaxWidth(), interactionSource = interactions,
        containerColor = Color.Transparent, focusedScale = 1f, shape = RoundedCornerShape(TvLayout.RowRadius)) {
        TvRowContent(title, detail, icon) { trailing(focused) }
    }
}

@Composable
private fun TvRowContent(title: String, detail: String, icon: ImageVector?, modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit) {
    Row(modifier.defaultMinSize(minHeight = if (detail.isBlank()) TvLayout.RowHeight else TvLayout.TwoLineRowHeight)
        .padding(horizontal = TvLayout.RowHorizontalPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvLayout.Gap)) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(TvLayout.RowIconSize))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = LocalContentColor.current.copy(alpha = .74f),
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun NeutralSwitch(checked: Boolean, focused: Boolean, busy: Boolean) {
    val position by animateFloatAsState(if (checked) 1f else 0f, tween(140), label = "Switch position")
    val rotation = if (busy) rememberInfiniteTransition(label = "Switch progress").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)), label = "Progress rotation",
    ) else null
    val track = when {
        checked && focused -> FlickColors.FocusForeground
        checked -> FlickColors.FocusBackground
        focused -> lerp(FlickColors.FocusBackground, FlickColors.FocusForeground, .3f)
        else -> lerp(FlickColors.SurfaceRaised, FlickColors.Foreground, .3f)
    }
    val thumb = if (checked == focused) FlickColors.FocusBackground else FlickColors.FocusForeground
    val progress = if (checked == focused) FlickColors.FocusForeground else FlickColors.FocusBackground
    Canvas(Modifier.width(36.dp).height(20.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val radius = 6.dp.toPx()
        val inset = 4.dp.toPx() + radius
        val center = Offset(inset + position * (size.width - inset * 2), size.height / 2)
        drawCircle(thumb, radius, center)
        rotation?.let {
            val arcRadius = 3.5.dp.toPx()
            drawArc(progress, startAngle = it.value, sweepAngle = 250f, useCenter = false,
                topLeft = center - Offset(arcRadius, arcRadius), size = Size(arcRadius * 2, arcRadius * 2),
                style = Stroke(1.25.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
