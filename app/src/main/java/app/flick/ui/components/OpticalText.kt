package app.flick.ui.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnitType
import androidx.tv.material3.Text
import java.text.Bidi

/** Align the visible start of a short label, keeping native text, baselines and layout width. */
@Composable
@OptIn(ExperimentalTextApi::class)
internal fun OpticalText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val resolver = LocalFontFamilyResolver.current
    val resolved = remember(resolver, style.fontFamily, style.fontWeight, style.fontStyle, style.fontSynthesis) {
        runCatching {
            resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
                style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All)
        }.getOrNull()
    }
    val resolvedTypeface = resolved?.value as? Typeface
    val density = LocalDensity.current
    val locales = LocalConfiguration.current.locales
    val direction = LocalLayoutDirection.current
    var ellipsized by remember(text, style) { mutableStateOf(false) }
    val startBearing = remember(text, style, resolvedTypeface, density, locales, direction) {
        val characters = text.toCharArray()
        if (direction != LayoutDirection.Ltr || resolvedTypeface == null || text.isEmpty() ||
            style.fontSize.type != TextUnitType.Sp || Bidi.requiresBidi(characters, 0, characters.size)) 0
        else {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = resolvedTypeface
                textSize = with(density) { style.fontSize.toPx() }
                textLocales = locales
                fontFeatureSettings = style.fontFeatureSettings
                letterSpacing = when (style.letterSpacing.type) {
                    TextUnitType.Sp -> with(density) { style.letterSpacing.toPx() } / textSize
                    TextUnitType.Em -> style.letterSpacing.value
                    else -> 0f
                }
                style.textGeometricTransform?.let { textScaleX = it.scaleX; textSkewX = it.skewX }
            }
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            if (bounds.isEmpty) 0 else bounds.left
        }
    }
    Text(text, style = style, color = color, maxLines = 1, softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { ellipsized = it.lineCount > 0 && it.isLineEllipsized(0) },
        modifier = modifier.layout { measurable, constraints ->
            val label = measurable.measure(constraints)
            layout(label.width, label.height) {
                label.placeRelative(if (ellipsized) 0 else -startBearing, 0)
            }
        })
}
