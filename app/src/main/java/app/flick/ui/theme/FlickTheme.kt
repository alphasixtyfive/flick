package app.flick.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import app.flick.R

@OptIn(ExperimentalTextApi::class)
private val FlickFont = FontFamily(
    Font(R.font.google_sans_flex_tv, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.google_sans_flex_tv, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.google_sans_flex_tv, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

object FlickColors {
    val Background = Color(0xFF111720)
    val Surface = Color(0xFF1B232E)
    val SurfaceRaised = Color(0xFF252F3D)
    val Foreground = Color(0xFFEDF0F5)
    val Secondary = Color(0xFFB7BFCA)
    val FocusBackground = Foreground
    val FocusForeground = Color(0xFF172232)
}

private val Colors = darkColorScheme(
    primary = FlickColors.Foreground,
    onPrimary = FlickColors.FocusForeground,
    secondary = FlickColors.Secondary,
    onSecondary = FlickColors.FocusForeground,
    background = FlickColors.Background,
    onBackground = FlickColors.Foreground,
    surface = FlickColors.Surface,
    onSurface = FlickColors.Foreground,
    surfaceVariant = FlickColors.SurfaceRaised,
    onSurfaceVariant = FlickColors.Secondary,
    inverseSurface = FlickColors.FocusBackground,
    inverseOnSurface = FlickColors.FocusForeground,
    surfaceTint = Color.Transparent,
)

private fun type(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FlickFont,
    fontSynthesis = FontSynthesis.None,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

private val Type = Typography(
    displayLarge = type(72, 76),
    headlineLarge = type(32, 40, FontWeight.Medium),
    headlineMedium = type(28, 36, FontWeight.Medium),
    titleLarge = type(24, 30, FontWeight.Medium),
    titleMedium = type(20, 26, FontWeight.Medium),
    bodyLarge = type(18, 26),
    bodyMedium = type(16, 22),
    bodySmall = type(14, 20),
    labelLarge = type(16, 20, FontWeight.Medium),
).let { typography ->
    // Keep every default style in the same family without changing its sizes or weights.
    typography.copy(
        displayMedium = typography.displayMedium.copy(fontFamily = FlickFont, fontSynthesis = FontSynthesis.None),
        displaySmall = typography.displaySmall.copy(fontFamily = FlickFont, fontSynthesis = FontSynthesis.None),
        headlineSmall = typography.headlineSmall.copy(fontFamily = FlickFont, fontSynthesis = FontSynthesis.None),
        titleSmall = typography.titleSmall.copy(fontFamily = FlickFont, fontSynthesis = FontSynthesis.None),
        labelMedium = typography.labelMedium.copy(fontFamily = FlickFont, fontSynthesis = FontSynthesis.None),
        labelSmall = typography.labelSmall.copy(fontFamily = FlickFont, fontSynthesis = FontSynthesis.None),
    )
}

private fun TextStyle.larger() = copy(fontSize = fontSize * 1.12f, lineHeight = lineHeight * 1.12f)

@Composable
fun FlickTheme(largeText: Boolean = false, content: @Composable () -> Unit) {
    val typography = remember(largeText) {
        if (!largeText) Type else Type.copy(
            titleLarge = Type.titleLarge.larger(), titleMedium = Type.titleMedium.larger(), titleSmall = Type.titleSmall.larger(),
            bodyLarge = Type.bodyLarge.larger(), bodyMedium = Type.bodyMedium.larger(), bodySmall = Type.bodySmall.larger(),
            labelLarge = Type.labelLarge.larger(), labelMedium = Type.labelMedium.larger(), labelSmall = Type.labelSmall.larger(),
        )
    }
    MaterialTheme(colorScheme = Colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides FlickColors.Foreground, content = content)
    }
}

private val LocalOverlayType = compositionLocalOf { false }

/** Menus use a quieter type scale than the home screen, including nested editors. */
@Composable
internal fun TvOverlayTheme(content: @Composable () -> Unit) {
    if (LocalOverlayType.current) { content(); return }
    val current = MaterialTheme.typography
    val compact = remember(current) {
        val scale = current.bodyLarge.fontSize.value / 18f
        fun TextStyle.size(size: Int, line: Int) = copy(fontSize = size.sp * scale, lineHeight = line.sp * scale)
        current.copy(
            titleLarge = current.titleLarge.size(20, 26), titleMedium = current.titleMedium.size(15, 20),
            titleSmall = current.titleSmall.size(14, 20), bodyLarge = current.bodyLarge.size(14, 20),
            bodyMedium = current.bodyMedium.size(13, 18), bodySmall = current.bodySmall.size(11, 16),
            labelLarge = current.labelLarge.size(13, 18), labelMedium = current.labelMedium.size(11, 16),
            labelSmall = current.labelSmall.size(11, 16),
        )
    }
    CompositionLocalProvider(LocalOverlayType provides true) {
        MaterialTheme(typography = compact, content = content)
    }
}
