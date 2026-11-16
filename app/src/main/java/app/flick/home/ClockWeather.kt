package app.flick.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import app.flick.ui.components.OpticalText
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout
import app.flick.weather.WeatherReading
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun ClockWeather(clock24: Boolean, showWeather: Boolean, weatherLocationSet: Boolean, weather: WeatherReading?, scale: Float) {
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
    val periodStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = (11 * scale).sp, lineHeight = (16 * scale).sp,
    )
    val metadataStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = (14 * scale).sp, lineHeight = (20 * scale).sp,
    )
    fun textWidth(value: String, style: TextStyle) = textMeasurer.measure(value, style, maxLines = 1, softWrap = false).size.width
    val sampleDay = remember { LocalDateTime.of(2000, 1, 1, 0, 0) }
    val longestTime = sampleDay.withHour(if (clock24) 0 else 10).format(timeFormat)
    val shortestTime = sampleDay.withHour(if (clock24) 0 else 1).format(timeFormat)
    val periodWidths = if (clock24) emptyList() else listOf(0, 12).map { hour ->
        textWidth(sampleDay.withHour(hour).format(periodFormat), periodStyle)
    }
    val periodGap = with(density) { (5 * scale).dp.roundToPx() }
    val clockWidth = textWidth(longestTime, clockStyle) + if (clock24) 0 else periodGap + periodWidths.max()
    // The shorter 12-hour reading must support the metadata too; its suffix follows the digits naturally.
    val metadataWidth = textWidth(shortestTime, clockStyle) + if (clock24) 0 else periodGap + periodWidths.min()
    val weatherVisible = showWeather && weatherLocationSet
    val temperature = weather?.let { "${it.temperature}°" }
    val iconSize = (14 * scale).dp
    val iconGap = (3 * scale).dp
    val metadataGap = (6 * scale).dp
    val weatherWidth = if (weatherVisible) {
        val numberWidth = maxOf(textWidth("-88°", metadataStyle), temperature?.let { textWidth(it, metadataStyle) } ?: 0)
        with(density) { numberWidth.toDp() } + iconSize + iconGap
    } else 0.dp
    val dateWidth = with(density) { metadataWidth.toDp() } - if (weatherVisible) weatherWidth + metadataGap else 0.dp
    val dateCandidates = dateFormats.map { now.format(it) }.distinct()
    val dateLabel = dateCandidates.firstOrNull { with(density) { textWidth(it, metadataStyle).toDp() } <= dateWidth }
        ?: dateCandidates.last()
    val dateStyle = if (with(density) { textWidth(dateLabel, metadataStyle).toDp() } <= dateWidth) metadataStyle
        else metadataStyle.copy(fontSize = (12 * scale).sp)
    val clockLineHeight = with(density) { (TvLayout.ClockLineHeight * scale).toDp() }
    val weatherHeight = with(density) { metadataStyle.lineHeight.toDp() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            now = LocalDateTime.now()
        }
    }
    // Width comes from a stable time template, never the date or a newly arrived weather reading.
    Column(Modifier.width(with(density) { clockWidth.toDp() }), horizontalAlignment = Alignment.Start) {
        Row(Modifier.fillMaxWidth().height(clockLineHeight), horizontalArrangement = Arrangement.Start) {
            OpticalText(now.format(timeFormat), style = clockStyle,
                modifier = Modifier.alignByBaseline())
            if (!clock24) Text(now.format(periodFormat),
                style = periodStyle, color = FlickColors.Secondary,
                maxLines = 1, softWrap = false,
                modifier = Modifier.padding(start = (5 * scale).dp).alignByBaseline())
        }
        Row(Modifier.width(with(density) { metadataWidth.toDp() }), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(metadataGap)) {
            OpticalText(dateLabel, style = dateStyle, color = FlickColors.Secondary,
                modifier = Modifier.weight(1f, fill = false))
            if (weatherVisible) {
                // Reserve the weather width before loading, keeping the date still.
                Box(Modifier.width(weatherWidth).height(weatherHeight), contentAlignment = Alignment.CenterStart) {
                    weather?.let { weather ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(iconGap)) {
                            Icon(if (weather.code == 0) Icons.Default.WbSunny else Icons.Default.Cloud,
                                contentDescription = null, modifier = Modifier.size(iconSize))
                            Text(temperature.orEmpty(), style = metadataStyle, color = FlickColors.Secondary,
                                maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

