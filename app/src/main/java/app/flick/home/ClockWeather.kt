package app.flick.home

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout
import app.flick.ui.theme.homeMetadataStyle
import app.flick.weather.WeatherReading
import app.flick.weather.icon
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal val ClockDigitsCentre = HorizontalAlignmentLine { first, second -> minOf(first, second) }

@Composable
internal fun ClockWeather(clock24: Boolean, showWeather: Boolean, weatherLocationSet: Boolean, weather: WeatherReading?, scale: Float,
    modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(clock24, locale) {
        DateTimeFormatter.ofPattern(if (clock24) "HH:mm" else "h:mm", locale)
    }
    val periodFormat = remember(locale) { DateTimeFormatter.ofPattern("a", locale) }
    val dateFormats = remember(locale) { listOf(
        DateTimeFormatter.ofPattern("EEE d MMM", locale),
        DateTimeFormatter.ofPattern("d MMM", locale),
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "Md"), locale),
    ) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val clockStyle = MaterialTheme.typography.headlineLarge.copy(
        fontSize = TvLayout.ClockFont * scale, lineHeight = TvLayout.ClockLineHeight * scale,
        fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum",
    )
    val digitsCentre = rememberClockDigitsCentre(clockStyle, timeFormat)
    val periodStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = (11 * scale).sp, lineHeight = (16 * scale).sp,
    )
    val metadataStyle = homeMetadataStyle(scale)
    fun textWidth(value: String, style: TextStyle) = textMeasurer.measure(value, style, maxLines = 1, softWrap = false).size.width
    val sampleDay = remember { LocalDateTime.of(2000, 1, 1, 0, 0) }
    val longestTime = sampleDay.withHour(if (clock24) 0 else 10).format(timeFormat)
    val periodWidths = if (clock24) emptyList() else listOf(0, 12).map { hour ->
        textWidth(sampleDay.withHour(hour).format(periodFormat), periodStyle)
    }
    val periodGap = with(density) { (5 * scale).dp.roundToPx() }
    val clockWidth = textWidth(longestTime, clockStyle) + if (clock24) 0 else periodGap + periodWidths.max()
    val blockWidth = with(density) { clockWidth.toDp() }
    val weatherVisible = showWeather && weatherLocationSet
    val temperature = weather?.let { "${it.temperature}°" }
    val iconSize = (14 * scale).dp
    val iconGap = (3 * scale).dp
    val metadataGap = (6 * scale).dp
    val weatherWidth = if (weatherVisible) {
        val numberWidth = maxOf(textWidth("-88°", metadataStyle), temperature?.let { textWidth(it, metadataStyle) } ?: 0)
        with(density) { numberWidth.toDp() } + iconSize + iconGap
    } else 0.dp
    val dateWidth = blockWidth - if (weatherVisible) weatherWidth + metadataGap else 0.dp
    val dateCandidates = dateFormats.map { now.format(it) }.distinct()
    val dateLabel = dateCandidates.firstOrNull { label ->
        with(density) { textWidth(label, metadataStyle).toDp() } <= dateWidth
    } ?: dateCandidates.last()
    val clockLineHeight = with(density) { (TvLayout.ClockLineHeight * scale).toDp() }
    val metadataHeight = with(density) { metadataStyle.lineHeight.toDp() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = LocalDateTime.now()
        }
    }
    // Width comes from a stable time template, never the date or a newly arrived weather reading.
    Column(modifier.width(blockWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(clockLineHeight), horizontalArrangement = Arrangement.Center) {
            Text(now.format(timeFormat), style = clockStyle, maxLines = 1, softWrap = false,
                modifier = Modifier.clockDigitsCentre(digitsCentre).alignByBaseline())
            if (!clock24) Text(now.format(periodFormat),
                style = periodStyle, color = FlickColors.Secondary,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(start = (5 * scale).dp).alignByBaseline())
        }
        Row(Modifier.fillMaxWidth().height(metadataHeight), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metadataGap, Alignment.CenterHorizontally)) {
            Text(dateLabel, style = metadataStyle, color = FlickColors.Secondary,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (weatherVisible) {
                // Reserve the weather width before loading, keeping the date still.
                Box(Modifier.width(weatherWidth).height(metadataHeight), contentAlignment = Alignment.Center) {
                    weather?.let { weather ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(iconGap)) {
                            Icon(weather.icon, contentDescription = weather.condition.label, modifier = Modifier.size(iconSize))
                            Text(temperature.orEmpty(), style = metadataStyle, color = FlickColors.Secondary,
                                maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberClockDigitsCentre(style: TextStyle, formatter: DateTimeFormatter): Float {
    val resolvedTypeface = LocalFontFamilyResolver.current.resolve(
        style.fontFamily, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.None,
    ).value as Typeface
    val fontSizePx = with(LocalDensity.current) { style.fontSize.toPx() }
    val zero = formatter.decimalStyle.zeroDigit
    return remember(resolvedTypeface, fontSizePx, zero, formatter.locale, style.fontFeatureSettings) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = resolvedTypeface
            textSize = fontSizePx
            textLocale = formatter.locale
            fontFeatureSettings = style.fontFeatureSettings
        }
        // A stable sample keeps the alignment still as the displayed time changes.
        val digits = CharArray(10) { zero + it }.concatToString()
        val bounds = Rect()
        paint.getTextBounds(digits, 0, digits.length, bounds)
        if (bounds.isEmpty) paint.fontMetrics.let { (it.ascent + it.descent) / 2f }
        else (bounds.top + bounds.bottom) / 2f
    }
}

private fun Modifier.clockDigitsCentre(centreFromBaseline: Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val centre = (placeable[FirstBaseline] + centreFromBaseline).roundToInt()
    layout(placeable.width, placeable.height, alignmentLines = mapOf(ClockDigitsCentre to centre)) {
        placeable.placeRelative(0, 0)
    }
}

