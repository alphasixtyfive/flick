package app.flick

import android.content.Context
import app.flick.apps.AndroidCatalogue
import app.flick.home.HomePolicy
import app.flick.homeassistant.HomeAssistantRepository
import app.flick.integration.BridgeCommand
import app.flick.integration.FlickBridgeClient
import app.flick.notifications.NotificationController
import app.flick.profiles.ProfileSecurity
import app.flick.settings.SettingsStore
import app.flick.wallpaper.WallpaperRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Process-owned features shared by the home screen and its optional background connection. */
class FlickRuntime(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = SettingsStore(context)
    val profiles = ProfileSecurity(context)
    val catalogue = AndroidCatalogue(context)
    val homeAssistant = HomeAssistantRepository(context, scope)
    val wallpapers = WallpaperRepository(context, scope)
    val profileLock = Mutex()
    val privilegedJobs = ConcurrentHashMap.newKeySet<Job>()
    @Volatile var switchingProfile = false
    private var visible = false
    private val commands = LauncherCommands(this)
    val notifications = NotificationController(context, scope) { action ->
        if (HomePolicy.canManage(store.state.value)) scope.launch {
            runCatching { bridge.notificationAction(action.noticeId, action.actionId) }
        }
    }
    val bridge = FlickBridgeClient(context, scope, onCommand = { command: BridgeCommand ->
        withContext(Dispatchers.Main.immediate) { commands.execute(command) }
    }, status = { commands.status() })

    init {
        scope.launch {
            homeAssistant.credentialRevision.drop(1).collect {
                runCatching { bridge.reconnect() }
            }
        }
        scope.launch {
            bridge.state.filter { it.deviceId.isNotEmpty() }.map { it.enabled && it.notificationsEnabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) notifications.dismissAll()
            }
        }
        scope.launch {
            bridge.state.filter { it.deviceId.isNotEmpty() }.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled && visible) notifications.enableBackground(true)
                else if (!enabled) {
                    notifications.enableBackground(false)
                }
            }
        }
        scope.launch {
            store.state.map { it.activeProfileId to HomePolicy.profile(it).child }.distinctUntilChanged().drop(1).collect {
                notifications.dismissAll()
            }
        }
        scope.launch {
            combine(store.state, notifications.state, bridge.state.map { it.connection }.distinctUntilChanged()) { _, _, _ -> Unit }
                .collectLatest {
                    delay(100)
                    runCatching { bridge.publishStatus() }
                }
        }
    }

    fun onBackgroundServiceStarted() { scope.launch { runCatching { bridge.reconnect() } } }
    fun onBackgroundServiceStopped() { notifications.dismissAll() }

    /** Call from a visible activity: Android restricts starting services from the background. */
    fun resume() {
        visible = true
        notifications.refreshPermissions()
        if (bridge.state.value.enabled) notifications.enableBackground(true)
    }
    fun pause() { visible = false }
}
