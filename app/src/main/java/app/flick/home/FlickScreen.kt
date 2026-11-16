package app.flick.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import app.flick.ui.theme.FlickColors
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import app.flick.settings.SettingsPage
import app.flick.ui.theme.TvLayout
import app.flick.ui.components.TvSettingRow
import app.flick.apps.AppEntry
import app.flick.apps.ContinueItem
import app.flick.profiles.Profile
import app.flick.profiles.ProfileAvatar
import app.flick.settings.NativeSettingsPanel
import app.flick.ui.components.TvAction
import app.flick.ui.components.TvFocusSurface
import app.flick.ui.components.RemotePress
import app.flick.ui.components.navigationFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import app.flick.ui.icons.FlickIcons
import app.flick.wallpaper.WallpaperBackground
import app.flick.wallpaper.WallpaperPhoto
import kotlinx.coroutines.delay

internal sealed interface HomePanel {
    data object Settings : HomePanel
    data object AllApps : HomePanel
    data object Profiles : HomePanel
    data object HomeAssistantControls : HomePanel
    data object Wallpaper : HomePanel
    data object Weather : HomePanel
    data object HomeAssistantSetup : HomePanel
    data class HomeEntities(val fromControls: Boolean = false) : HomePanel
    data object Integration : HomePanel
    data object ManageProfiles : HomePanel
    data class EditProfile(val profile: Profile) : HomePanel
    data class Pin(val profile: Profile) : HomePanel
    data class AppOptions(val app: AppEntry) : HomePanel
    data class ContinueOptions(val item: ContinueItem) : HomePanel
}

@Composable
fun FlickScreen(state: HomeState, actions: HomeActions) {
    if (state.recoveryRequired) {
        RecoveryScreen(actions.openRecoverySettings)
        return
    }
    val profile = HomePolicy.profile(state.settings)
    val child = profile.child
    val apps = HomePolicy.visibleApps(state.settings, state.apps)
    val byPackage = apps.associateBy { it.packageName }
    val favorites = if (child) apps.map { it.packageName } else state.settings.favorites ?: apps.take(5).map { it.packageName }
    var panel by remember { mutableStateOf<HomePanel?>(null) }
    var settingsPage by remember { mutableStateOf(SettingsPage.Root) }
    var appOptionsParent by remember { mutableStateOf<HomePanel?>(null) }
    var retainedPanel by remember { mutableStateOf<HomePanel?>(null) }
    val panelVisibility = remember { MutableTransitionState(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val allAppsScroll = rememberLazyGridState()
    var focusedAllApp by remember { mutableStateOf<String?>(null) }
    val appsScroll = rememberLazyListState()
    val continueScroll = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var returnFocus by remember { mutableStateOf("profile") }
    var hadPanel by remember { mutableStateOf(false) }
    // A native dialog blocks input in its own window and preserves the caller's focus.
    val backgroundEnabled = panel?.isDialog() == true ||
        (panel == null && !panelVisibility.currentState && !panelVisibility.targetState)
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun homeFocus(id: String, leftBoundary: Boolean = false, rightBoundary: Boolean = false): Modifier = Modifier
        .focusProperties {
            canFocus = backgroundEnabled
            left = if (leftBoundary) FocusRequester.Cancel else FocusRequester.Default
            right = if (rightBoundary) FocusRequester.Cancel else FocusRequester.Default
        }
        .focusRequester(requesters.getOrPut(id) { FocusRequester() })
        .onFocusChanged { if (it.isFocused && backgroundEnabled && !hadPanel) returnFocus = id }
    var moving by remember { mutableStateOf<String?>(null) }
    var moveOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    val movePress = remember { RemotePress() }
    val displayedApps = (if (moving != null) moveOrder else favorites).mapNotNull(byPackage::get)
    val lastTopAction = when {
        state.settings.showWallpaper -> "wallpaper"
        !child && state.settings.showSettings -> "settings"
        !child && state.settings.showHomeAssistant && state.haConfiguration.configured -> "home-assistant"
        else -> "profile"
    }
    LaunchedEffect(moving, windowFocused, byPackage.keys) {
        movePress.cancel()
        if (moving != null && moving !in byPackage) moving = null
    }
    DisposableEffect(movePress) { onDispose { movePress.cancel() } }
    SideEffect {
        val valid = displayedApps.map { "app:" + it.packageName }.toSet() +
            state.continueItems.map { "show:" + it.id } + setOf("profile", "home-assistant", "settings", "wallpaper", "all-apps")
        requesters.keys.retainAll(valid)
    }
    fun openPanel(destination: HomePanel) {
        moving = null
        movePress.cancel()
        if (destination is HomePanel.Pin) actions.clearProfileError()
        hadPanel = true
        panel = destination
    }
    fun openSettings() {
        settingsPage = SettingsPage.Root
        openPanel(HomePanel.Settings)
        panelVisibility.targetState = true
    }
    fun closePanel() {
        panelVisibility.targetState = false
        panel = null
    }
    LaunchedEffect(state.message) {
        if (state.message != null) { delay(6000); actions.dismissMessage() }
    }
    val fontScale = if (state.settings.largeText) 1.12f else 1f

    LaunchedEffect(panel) {
        if (panel != null) {
            if (panel?.isDialog() == false) retainedPanel = panel
            hadPanel = true
        }
        panelVisibility.targetState = panel != null && panel?.isDialog() == false
    }
    LaunchedEffect(panel, windowFocused, panelVisibility.isIdle, panelVisibility.currentState, panelVisibility.targetState) {
        if (hadPanel && windowFocused && panelVisibility.isIdle && !panelVisibility.currentState && !panelVisibility.targetState && panel == null) {
            val appIndex = displayedApps.indexOfFirst { "app:" + it.packageName == returnFocus }
            val continueIndex = state.continueItems.indexOfFirst { "show:" + it.id == returnFocus }
            if (appIndex >= 0) appsScroll.scrollToItem(appIndex)
            if (continueIndex >= 0) continueScroll.scrollToItem(continueIndex)
            withFrameNanos { }
            val restored = runCatching {
                val requester = requesters[returnFocus] ?: return@runCatching false
                requester.requestFocus()
                true
            }.getOrDefault(false)
            if (!restored) runCatching { requesters["profile"]?.requestFocus() }
            hadPanel = false
            retainedPanel = null
        }
    }
    LaunchedEffect(moving, moveOrder) {
        val target = moving ?: return@LaunchedEffect
        val index = displayedApps.indexOfFirst { it.packageName == target }
        if (index >= 0) {
            appsScroll.scrollToItem(index)
            withFrameNanos { }
            runCatching { requesters["app:" + target]?.requestFocus() }
        }
    }
    LaunchedEffect(state.settings.activeProfileId, child) {
        closePanel()
        moving = null
    }
    LaunchedEffect(state.homeRequestVersion) {
        if (state.homeRequestVersion > 0) {
            closePanel()
            moving = null
        }
    }
    BackHandler(enabled = panel == null) {
        moving = null
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xff24374a), Color(0xff101820)))).navigationFeedback()
        .onPreviewKeyEvent { event ->
            if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_SETTINGS) {
                if (event.type == KeyEventType.KeyDown && !child) openSettings()
                return@onPreviewKeyEvent true
            }
            val packageName = moving ?: return@onPreviewKeyEvent false
            if (panel != null || !backgroundEnabled) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                moveOrder = HomePolicy.moveFavorite(moveOrder, byPackage.keys, packageName,
                    if (event.key == Key.DirectionLeft) -1 else 1)
                true
            } else if (event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                true
            } else if (event.nativeKeyEvent.keyCode in listOf(
                    android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_A,
                    android.view.KeyEvent.KEYCODE_BUTTON_SELECT)) {
                val key = event.nativeKeyEvent
                when (event.type) {
                    KeyEventType.KeyDown -> movePress.down(key.keyCode, key.repeatCount)
                    KeyEventType.KeyUp -> if (movePress.up(key.keyCode, key.isCanceled) == RemotePress.Release.Click) {
                        actions.updateSettings(state.settings, state.settings.copy(favorites = moveOrder.filter(byPackage::containsKey)))
                        moving = null
                    }
                }
                true
            } else false
        }) {
        WallpaperBackground(
            photo = state.wallpaperUri?.let { WallpaperPhoto(it) },
            dimAmount = state.wallpaperSettings.dimAmount,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Black.copy(alpha = .48f), Color.Transparent, Color.Black.copy(alpha = .86f),
        ))))

        val clockLineHeight = with(LocalDensity.current) { (TvLayout.ClockLineHeight * fontScale).toDp() }
        Row(Modifier.fillMaxWidth().padding(horizontal = TvLayout.SafeHorizontal, vertical = TvLayout.SafeVertical),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.height(clockLineHeight), contentAlignment = Alignment.CenterStart) {
                TvFocusSurface(
                    onClick = { openPanel(HomePanel.Profiles) }, shape = CircleShape,
                    onLongClick = if (child) null else ({ openSettings() }),
                    modifier = homeFocus("profile", leftBoundary = true, rightBoundary = lastTopAction == "profile").size(TvLayout.TopActionSize).semantics {
                        contentDescription = profile.name
                        stateDescription = "Active profile"
                    },
                ) {
                    ProfileAvatar(profile, TvLayout.TopActionSize)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                Row(Modifier.height(clockLineHeight), horizontalArrangement = Arrangement.spacedBy(TvLayout.Gap),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!child && state.settings.showHomeAssistant && state.haConfiguration.configured) TvAction(
                        label = "Home Assistant", icon = FlickIcons.HomeAssistant,
                        modifier = homeFocus("home-assistant", rightBoundary = lastTopAction == "home-assistant"),
                        onClick = { openPanel(HomePanel.HomeAssistantControls) },
                    )
                    if (!child && state.settings.showSettings) TvAction(
                        label = "Settings", icon = Icons.Default.Settings,
                        modifier = homeFocus("settings", rightBoundary = lastTopAction == "settings"),
                        onClick = { openSettings() },
                    )
                    if (state.settings.showWallpaper) TvAction(
                        label = "Next wallpaper", icon = Icons.Default.PhotoLibrary,
                        modifier = homeFocus("wallpaper", rightBoundary = true), onClick = actions.rotateWallpaper,
                    )
                }
                ClockWeather(state.settings.clock24, state.settings.showWeather, state.settings.weatherLocation != null, state.weather, fontScale)
            }
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = TvLayout.SafeHorizontal, end = TvLayout.SafeHorizontal, bottom = 54.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (!child && state.settings.showContinue && state.continueItems.isNotEmpty()) Column(
                verticalArrangement = Arrangement.spacedBy(TvLayout.Gap),
            ) {
                Text("Continue watching", fontSize = (16 * fontScale).sp, color = Color.White.copy(alpha = .84f))
                LazyRow(state = continueScroll, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(8.dp)) {
                    itemsIndexed(state.continueItems, key = { _, item -> item.id }) { index, item ->
                        ContinueCard(item, modifier = homeFocus("show:" + item.id,
                            leftBoundary = index == 0, rightBoundary = index == state.continueItems.lastIndex), onClick = { actions.openContinue(item) },
                            onLongClick = { openPanel(HomePanel.ContinueOptions(item)) })
                    }
                }
            }
            LazyRow(state = appsScroll, horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(8.dp)) {
                itemsIndexed(displayedApps, key = { _, app -> app.packageName }) { index, app ->
                    AppCard(app, modifier = homeFocus("app:" + app.packageName,
                        leftBoundary = index == 0, rightBoundary = child && index == displayedApps.lastIndex),
                        compact = state.settings.compactTiles, moving = moving == app.packageName,
                        onClick = {
                            if (moving != null) {
                                actions.updateSettings(state.settings, state.settings.copy(favorites = moveOrder))
                                moving = null
                            } else actions.launchApp(app)
                        },
                        onLongClick = if (child) null else ({ appOptionsParent = null; openPanel(HomePanel.AppOptions(app)) }))
                }
                if (!child) item(key = "all-apps") {
                    TvFocusSurface(onClick = { openPanel(HomePanel.AllApps) },
                        modifier = homeFocus("all-apps", leftBoundary = displayedApps.isEmpty(), rightBoundary = true)
                            .width(TvLayout.appTileWidth(state.settings.compactTiles)).aspectRatio(16f / 9f)) {
                        Icon(Icons.Default.Apps, "All apps", Modifier.size(40.dp).align(Alignment.Center))
                    }
                }
            }
            if (moving != null) Text("Left / Right to move · OK to save · Back to cancel", style = MaterialTheme.typography.bodySmall)
            if (state.loading && apps.isEmpty()) Text("Loading apps…", style = MaterialTheme.typography.bodyMedium)
            if (!state.loading && child && displayedApps.isEmpty()) Text("No allowed apps are installed.", style = MaterialTheme.typography.bodyMedium)
        }


        AnimatedVisibility(visibleState = panelVisibility, enter = slideInHorizontally(tween(180)) { it },
            exit = slideOutHorizontally(tween(160)) { it }, modifier = Modifier.align(Alignment.CenterEnd)
                .onPreviewKeyEvent { panel == null || panel?.isDialog() == true }) {
            val displayed = panel?.takeUnless { it.isDialog() } ?: retainedPanel
            if (displayed == HomePanel.Settings) NativeSettingsPanel(state, actions, page = settingsPage,
                onPageChange = { if (panel == displayed) settingsPage = it }, onClose = { if (panel == displayed) closePanel() },
                onWeather = { if (panel == displayed) openPanel(HomePanel.Weather) },
                onWallpaper = { if (panel == displayed) openPanel(HomePanel.Wallpaper) },
                onProfiles = { if (panel == displayed) openPanel(HomePanel.ManageProfiles) },
                onHomeAssistant = { if (panel == displayed) openPanel(HomePanel.HomeAssistantSetup) })
            else HomePanels(displayed, state, actions, onClose = { if (panel == displayed) closePanel() },
                onPanel = { if (panel == displayed) openPanel(it) })
        }
        panel?.takeIf { it.isDialog() }?.let { dialog ->
            HomeDialogs(dialog, state, actions, allAppsScroll, onClose = { if (panel == dialog) closePanel() },
                onAppOptionsBack = { if (appOptionsParent == null) closePanel() else appOptionsParent?.let { openPanel(it) } },
                onPanel = { destination ->
                    if (destination is HomePanel.AppOptions) appOptionsParent = panel.takeIf { it == HomePanel.AllApps }
                    openPanel(destination)
                },
                onMove = { app ->
                    returnFocus = "app:" + app.packageName
                    moving = app.packageName
                    moveOrder = favorites.filter(byPackage::containsKey)
                    closePanel()
                },
                onRemoveContinue = {
                    val item = (dialog as? HomePanel.ContinueOptions)?.item
                    if (item != null) {
                        val index = state.continueItems.indexOfFirst { it.id == item.id }
                        val neighbor = state.continueItems.getOrNull(index + 1) ?: state.continueItems.getOrNull(index - 1)
                        returnFocus = neighbor?.let { "show:" + it.id } ?: displayedApps.firstOrNull()?.let { "app:" + it.packageName } ?: "all-apps"
                        actions.removeContinue(item)
                        closePanel()
                    }
                }, focusedApp = focusedAllApp, onFocusedApp = { focusedAllApp = it })
        }
        state.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(22.dp)
                    .background(Color(0xee182330)).padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun RecoveryScreen(openAndroidSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xff111720)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Setup could not be read", style = MaterialTheme.typography.headlineSmall)
            Text("Flick kept the damaged setup private and locked its controls. Open Android app settings to clear its storage, then set it up again. Android will ask you to confirm.", style = MaterialTheme.typography.bodyLarge)
            TvSettingRow("Open Android app settings", icon = Icons.Default.Settings, onClick = openAndroidSettings)
        }
    }
}
