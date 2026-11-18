package app.flick.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.theme.FlickColors
import app.flick.ui.theme.TvLayout
import app.flick.ui.theme.TvOverlayTheme

/** This page stays mounted underneath app menus, retaining the grid and its focus target. */
@Composable
internal fun AllAppsDrawer(
    state: HomeState,
    actions: HomeActions,
    scroll: LazyGridState,
    focusedApp: String?,
    onFocusedApp: (String) -> Unit,
    onPanel: (HomePanel) -> Unit,
    onClose: () -> Unit,
    isInteractive: () -> Boolean,
) {
    val interactive = isInteractive()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val packages = remember(state.apps) { state.apps.map { it.packageName } }
    var lastFocusedIndex by remember { mutableIntStateOf(scroll.firstVisibleItemIndex) }
    val focusTarget = remember(packages) {
        drawerFocusTarget(packages, focusedApp, lastFocusedIndex)
    }
    var focusPending by remember(packages) { mutableStateOf(true) }

    BackHandler(enabled = interactive, onBack = onClose)
    LaunchedEffect(packages) {
        val index = packages.indexOf(focusTarget)
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            scroll.scrollToItem(index)
        }
    }

    TvOverlayTheme {
        val tileWidth = TvLayout.appTileWidth(compact = true)
        val captionStyle = MaterialTheme.typography.bodyMedium
        val captionHeight = with(LocalDensity.current) { captionStyle.lineHeight.toDp() }
        Column(
            Modifier.fillMaxSize().background(FlickColors.Background)
                // Pointer remotes must not click Home through the empty parts of this page.
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .padding(horizontal = TvLayout.SafeHorizontal, vertical = TvLayout.SafeVertical)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(TvLayout.SectionGap),
        ) {
            Text("All apps", style = MaterialTheme.typography.titleLarge)
            if (state.apps.isEmpty()) {
                Text(if (state.loading) "Loading apps…" else "No apps available",
                    style = MaterialTheme.typography.bodyMedium, color = FlickColors.Secondary)
            } else LazyVerticalGrid(
                columns = GridCells.Adaptive(tileWidth), state = scroll,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(state.apps, key = { _, app -> app.packageName }) { index, app ->
                    val focus = remember { FocusRequester() }
                    var placed by remember { mutableStateOf(false) }
                    LaunchedEffect(focusTarget, placed, focusPending, interactive, windowFocused) {
                        if (placed && focusPending && interactive && windowFocused && focusTarget == app.packageName) {
                            focus.requestFocus()
                            focusPending = false
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppCard(app, compact = true,
                            modifier = Modifier.focusRequester(focus).onPlaced { placed = true }.onFocusChanged {
                                if (it.isFocused && interactive && windowFocused &&
                                    (!focusPending || focusTarget == app.packageName)) {
                                    lastFocusedIndex = index
                                    onFocusedApp(app.packageName)
                                }
                            },
                            onClick = {
                                if (isInteractive()) {
                                    lastFocusedIndex = index
                                    onFocusedApp(app.packageName)
                                    actions.launchApp(app)
                                }
                            },
                            onLongClick = if (!HomePolicy.canManage(state.settings)) null else ({
                                if (isInteractive()) {
                                    lastFocusedIndex = index
                                    onFocusedApp(app.packageName)
                                    onPanel(HomePanel.AppOptions(app, fromAllApps = true))
                                }
                            }))
                        Text(app.name, style = captionStyle, maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.width(tileWidth).heightIn(min = captionHeight).clearAndSetSemantics {})
                    }
                }
            }
        }
    }
}

/** Package identity survives reorder; removal selects the nearest remaining position. */
internal fun drawerFocusTarget(packages: List<String>, focusedPackage: String?, lastIndex: Int): String? {
    if (packages.isEmpty()) return null
    return focusedPackage?.takeIf { it in packages } ?: packages[lastIndex.coerceIn(0, packages.lastIndex)]
}
