package app.flick.tv.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.tv.ui.theme.FlickColors
import app.flick.tv.ui.theme.TvLayout
import app.flick.tv.ui.theme.TvOverlayTheme

/** Caller owns visibility/slide animation; the header stays still while the content scrolls. */
@Composable
fun TvPanel(
    title: String,
    onBack: () -> Unit,
    focusKey: Any = title,
    preferredFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(title) { scroll.scrollTo(0) }
    PanelShell(title, onBack, focusKey, preferredFocus) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(TvLayout.PanelContentPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/** Large app/entity lists compose only visible rows while sharing the same panel and focus rules. */
@Composable
fun TvLazyPanel(
    title: String,
    onBack: () -> Unit,
    focusKey: Any = title,
    content: LazyListScope.() -> Unit,
) {
    val scroll = rememberLazyListState()
    LaunchedEffect(title) { scroll.scrollToItem(0) }
    PanelShell(title, onBack, focusKey) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f), state = scroll,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(TvLayout.PanelContentPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun PanelShell(
    title: String,
    onBack: () -> Unit,
    focusKey: Any,
    preferredFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val initialFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // Structural changes need a new target; returning from a dialog keeps the caller's focus.
    var focusPending by remember(title, focusKey) { mutableStateOf(true) }
    LaunchedEffect(title, focusKey, windowFocused, focusPending) {
        if (windowFocused && focusPending) {
            withFrameNanos { }
            val restored = preferredFocus != null && runCatching { preferredFocus.requestFocus() }.isSuccess
            if (!restored) runCatching { initialFocus.requestFocus() }
            focusPending = false
        }
    }
    BackHandler(onBack = onBack)
    TvOverlayTheme {
        Column(
            Modifier.width(TvLayout.PanelWidth).fillMaxHeight().background(FlickColors.Background)
                .padding(horizontal = TvLayout.PanelPadding, vertical = TvLayout.SafeVertical)
                .focusRequester(initialFocus).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(TvLayout.SectionGap),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = TvLayout.PanelHeadingInset))
            content()
        }
    }
}
