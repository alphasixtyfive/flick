package app.flick.tv.hometext

import androidx.compose.material.icons.Icons
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.tv.home.HomeActions
import app.flick.tv.home.HomeState
import app.flick.tv.integration.BridgeConnection
import app.flick.tv.ui.components.TvDialog
import app.flick.tv.ui.components.TvPanel
import app.flick.tv.ui.components.TvSettingRow
import app.flick.tv.ui.theme.FlickColors
import app.flick.tv.ui.theme.TvLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeTextPanel(state: HomeState, actions: HomeActions, onIntegration: () -> Unit, onBack: () -> Unit) {
    val enabled = state.bridge.homeTextEnabled
    val messageState = state.homeText
    val connected = enabled && state.bridge.paired && state.bridge.enabled &&
        state.bridge.connection == BridgeConnection.Connected && messageState.status == HomeTextStatus.Connected
    val status = when {
        !enabled -> "Off"
        !state.bridge.paired -> "Pair the TV connection first"
        !state.bridge.enabled -> "TV connection is paused"
        state.bridge.connection != BridgeConnection.Connected -> state.bridge.connection.label
        messageState.status == HomeTextStatus.Unsupported -> "Update the Flick integration in Home Assistant"
        messageState.status == HomeTextStatus.Unavailable -> "Text is unavailable"
        messageState.status == HomeTextStatus.Connected -> "Connected"
        else -> "Waiting for Home Assistant"
    }
    val setupUrl = remember(state.haConfiguration.url) {
        runCatching { HomeTextSetup.integrationUrl(state.haConfiguration.url) }.getOrNull()
    }
    var showSetup by remember(setupUrl) { mutableStateOf(false) }
    TvPanel("Home screen text", onBack = onBack) {
        TvSettingRow("Show message", checked = enabled, icon = Icons.Default.Visibility,
            onClick = { actions.setHomeTextEnabled(!enabled) })
        if (enabled && !connected) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary,
                modifier = Modifier.padding(start = TvLayout.RowTextInset, end = TvLayout.RowHorizontalPadding))
        }
        if (enabled && (!state.bridge.paired || !state.bridge.enabled)) {
            TvSettingRow("TV connection", icon = Icons.Default.Link, onClick = onIntegration)
        }
        val profile = state.settings.profiles.find { it.id == state.profileSession.authorizedProfileId }
        val preview = profile?.takeIf { enabled && state.bridge.paired &&
            state.bridge.connection == BridgeConnection.Connected && it.id == state.settings.activeProfileId }
            ?.let { messageState.visibleFor(it.id, it.child, state.profileSession.generation) }
        if (enabled && connected) {
            Text(preview?.text?.takeIf(String::isNotBlank) ?: "No message set",
                style = MaterialTheme.typography.bodyMedium, color = FlickColors.Secondary,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = TvLayout.RowTextInset, end = TvLayout.RowHorizontalPadding))
        }
        if (setupUrl != null) TvSettingRow("Edit in Home Assistant", icon = Icons.Default.QrCode2,
            onClick = { showSetup = true })
    }
    if (showSetup && setupUrl != null) HomeTextSetupDialog(setupUrl, onClose = { showSetup = false })
}

@Composable
private fun HomeTextSetupDialog(url: String, onClose: () -> Unit) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        try {
            image = withContext(Dispatchers.Default) {
                val size = HomeTextSetup.QR_SIZE
                Bitmap.createBitmap(HomeTextSetup.qrPixels(url), size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    TvDialog("Edit in Home Assistant", onDismiss = onClose) {
        Text("Scan with your phone, choose this TV, then Configure → Home screen text.", style = MaterialTheme.typography.bodyMedium)
        if (!failed) Box(Modifier.align(Alignment.CenterHorizontally).size(176.dp), contentAlignment = Alignment.Center) {
            image?.let {
                Image(it, contentDescription = "Home Assistant setup link",
                    modifier = Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp).size(160.dp),
                    filterQuality = FilterQuality.None)
            } ?: Text("Preparing link…", style = MaterialTheme.typography.bodySmall)
        }
        if (failed) Text(url, style = MaterialTheme.typography.bodySmall, color = FlickColors.Secondary)
        TvSettingRow("Done", onClick = onClose)
    }
}
