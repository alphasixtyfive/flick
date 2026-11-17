package app.flick.home

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

internal val ClockDigitsCentre = HorizontalAlignmentLine { first, second -> minOf(first, second) }

internal data class ClockTextAlignment(val centreFromBaseline: Float, val inkRight: Int)

@Composable
internal fun rememberClockTextAlignment(style: TextStyle, formatter: DateTimeFormatter, text: String): ClockTextAlignment {
    val typeface = LocalFontFamilyResolver.current.resolve(
        style.fontFamily, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.None,
    ).value as Typeface
    val fontSizePx = with(LocalDensity.current) { style.fontSize.toPx() }
    val paint = remember(typeface, fontSizePx, formatter.locale, style.fontFeatureSettings) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = fontSizePx
            textLocale = formatter.locale
            fontFeatureSettings = style.fontFeatureSettings
        }
    }
    val zero = formatter.decimalStyle.zeroDigit
    val centre = remember(paint, zero) {
        val digits = CharArray(10) { zero + it }.concatToString()
        val bounds = Rect()
        paint.getTextBounds(digits, 0, digits.length, bounds)
        if (bounds.isEmpty) paint.fontMetrics.let { (it.ascent + it.descent) / 2f }
        else (bounds.top + bounds.bottom) / 2f
    }
    val inkRight = remember(paint, text) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        if (bounds.isEmpty) paint.measureText(text).roundToInt() else bounds.right
    }
    return ClockTextAlignment(centre, inkRight)
}

/** Font bounds align the visible edge; tabular spacing and the surrounding layout stay stable. */
internal fun Modifier.clockTextAlignment(alignment: ClockTextAlignment) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val centre = (placeable[FirstBaseline] + alignment.centreFromBaseline).roundToInt()
    layout(placeable.width, placeable.height, alignmentLines = mapOf(ClockDigitsCentre to centre)) {
        placeable.place(placeable.width - alignment.inkRight, 0)
    }
}
