package app.flick.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** TV layout uses the device's real dp viewport, independently of HDMI output resolution. */
object TvLayout {
    val SafeHorizontal = 48.dp
    val SafeVertical = 28.dp
    val AppDockBottom = 54.dp
    val TopActionSize = 36.dp
    val PanelWidth = 300.dp
    val PanelPadding = 18.dp
    val RowHeight = 40.dp
    val TwoLineRowHeight = 56.dp
    val RowHorizontalPadding = 12.dp
    val RowIconSize = 18.dp
    val Gap = 12.dp
    val RowTextInset = RowHorizontalPadding + RowIconSize + Gap
    val SectionGap = 16.dp
    val ClockFont = 44.sp
    val ClockLineHeight = 48.sp
    val CardRadius = 20.dp
    val RowRadius = 12.dp
    val DialogRadius = 24.dp

    fun appTileWidth(compact: Boolean) = if (compact) 120.dp else 144.dp
}
