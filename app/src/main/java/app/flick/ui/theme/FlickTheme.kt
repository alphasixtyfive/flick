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
import app.flick.themes.AppearanceSettings
import app.flick.themes.ThemeCatalog
import app.flick.themes.ThemePalette

@OptIn(ExperimentalTextApi::class)
private val FlickFont = FontFamily(
    Font(R.font.google_sans_flex_tv, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.google_sans_flex_tv, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.google_sans_flex_tv, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

object FlickColors {
    val Background: Color @Composable get() = MaterialTheme.colorScheme.background
    val Surface: Color @Composable get() = MaterialTheme.colorScheme.surface
    val SurfaceRaised: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
    val Foreground: Color @Composable get() = MaterialTheme.colorScheme.onBackground
    val Secondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val FocusBackground: Color @Composable get() = MaterialTheme.colorScheme.inverseSurface
    val FocusForeground: Color @Composable get() = MaterialTheme.colorScheme.inverseOnSurface
}

private fun ThemePalette.colorScheme() = darkColorScheme(
    primary = Color(focusBackground),
    onPrimary = Color(focusForeground),
    secondary = Color(secondary),
    onSecondary = Color(focusForeground),
    background = Color(background),
    onBackground = Color(foreground),
    surface = Color(surface),
    onSurface = Color(foreground),
    surfaceVariant = Color(surfaceRaised),
    onSurfaceVariant = Color(secondary),
    inverseSurface = Color(focusBackground),
    inverseOnSurface = Color(focusForeground),
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
internal fun homeMetadataStyle(scale: Float): TextStyle = MaterialTheme.typography.bodySmall.copy(
    fontSize = 14.sp * scale, lineHeight = 20.sp * scale,
)

@Composable
fun FlickTheme(largeText: Boolean = false, appearance: AppearanceSettings = AppearanceSettings(), content: @Composable () -> Unit) {
    val colors = remember(appearance) { ThemeCatalog.resolve(appearance).colors.colorScheme() }
    val typography = remember(largeText) {
        if (!largeText) Type else Type.copy(
            titleLarge = Type.titleLarge.larger(), titleMedium = Type.titleMedium.larger(), titleSmall = Type.titleSmall.larger(),
            bodyLarge = Type.bodyLarge.larger(), bodyMedium = Type.bodyMedium.larger(), bodySmall = Type.bodySmall.larger(),
            labelLarge = Type.labelLarge.larger(), labelMedium = Type.labelMedium.larger(), labelSmall = Type.labelSmall.larger(),
        )
    }
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides FlickColors.Foreground, content = content)
    }
}

private val LocalOverlayType = compositionLocalOf { false }

/** TV menu typography is shared by panels, dialogs and nested editors. */
@Composable
internal fun TvOverlayTheme(content: @Composable () -> Unit) {
    if (LocalOverlayType.current) { content(); return }
    val current = MaterialTheme.typography
    val compact = remember(current) {
        val scale = current.bodyLarge.fontSize.value / 18f
        fun TextStyle.size(size: Int, line: Int) = copy(fontSize = size.sp * scale, lineHeight = line.sp * scale)
        current.copy(
            titleLarge = current.titleLarge.size(22, 28).copy(fontWeight = FontWeight.Normal),
            titleMedium = current.titleMedium.size(16, 24),
            titleSmall = current.titleSmall.size(14, 20), bodyLarge = current.bodyLarge.size(16, 24),
            bodyMedium = current.bodyMedium.size(14, 20), bodySmall = current.bodySmall.size(12, 16),
            labelLarge = current.labelLarge.size(14, 20), labelMedium = current.labelMedium.size(12, 16),
            labelSmall = current.labelSmall.size(11, 16),
        )
    }
    CompositionLocalProvider(LocalOverlayType provides true) {
        MaterialTheme(typography = compact, content = content)
    }
}
