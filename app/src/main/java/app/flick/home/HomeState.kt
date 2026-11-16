package app.flick.home

import app.flick.apps.AppEntry
import app.flick.apps.ContinueItem
import app.flick.homeassistant.HaConfiguration
import app.flick.homeassistant.HaControl
import app.flick.homeassistant.HaConnectionState
import app.flick.homeassistant.HaControlSpec
import app.flick.homeassistant.HaEntity
import app.flick.profiles.Profile
import app.flick.settings.LauncherSettings
import app.flick.wallpaper.WallpaperSettings
import app.flick.weather.PlaceResult
import app.flick.weather.WeatherReading
import app.flick.integration.BridgeState
import app.flick.notifications.NotificationState

/** Immutable screen input. Native repositories remain responsible for authorization and I/O. */
data class HomeState(
    val settings: LauncherSettings = LauncherSettings(),
    val apps: List<AppEntry> = emptyList(),
    val continueItems: List<ContinueItem> = emptyList(),
    val weather: WeatherReading? = null,
    val places: List<PlaceResult> = emptyList(),
    val searchingPlaces: Boolean = false,
    val wallpaperUri: String? = null,
    val wallpaperSettings: WallpaperSettings = WallpaperSettings(),
    val homeControls: List<HaControl> = emptyList(),
    val haConfiguration: HaConfiguration = HaConfiguration(),
    val haEntities: List<HaEntity> = emptyList(),
    val haBusy: Boolean = false,
    val bridge: BridgeState = BridgeState(),
    val notifications: NotificationState = NotificationState(),
    val haConnectionState: HaConnectionState = HaConnectionState.Unknown,
    val haDiscoveryLoaded: Boolean = false,
    val recoveryRequired: Boolean = false,
    val loading: Boolean = true,
    val continueAccess: Boolean = false,
    val message: String? = null,
    val profileError: String? = null,
    val profileErrorTarget: String? = null,
    val homeRequestVersion: Int = 0,
)

data class HomeActions(
    val launchApp: (AppEntry) -> Unit,
    val openContinue: (ContinueItem) -> Unit,
    val updateSettings: (LauncherSettings, LauncherSettings) -> Unit,
    val appInfo: (AppEntry) -> Unit,
    val uninstallApp: (AppEntry) -> Unit,
    val removeContinue: (ContinueItem) -> Unit,
    val chooseProfile: (Profile, String, (Result<Unit>) -> Unit) -> Unit,
    val saveProfile: (Profile, String, (Result<Unit>) -> Unit) -> Unit,
    val deleteProfile: (Profile, (Result<Unit>) -> Unit) -> Unit,
    val chooseProfilePhoto: (Profile) -> Unit,
    val removeProfilePhoto: (Profile) -> Unit,
    val searchPlaces: (String) -> Unit,
    val rotateWallpaper: () -> Unit,
    val saveWallpaperSettings: (WallpaperSettings, WallpaperSettings) -> Unit,
    val chooseWallpaperFolder: () -> Unit,
    val chooseWallpaperPhotos: () -> Unit,
    val setHomePower: (HaControl, Boolean) -> Unit,
    val runHomeScene: (HaControl) -> Unit,
    val configureHomeAssistant: (String, String) -> Unit,
    val discoverHomeEntities: () -> Unit,
    val retryHomeAssistant: () -> Unit,
    val pairHomeAssistant: () -> Unit,
    val forgetIntegration: () -> Unit,
    val setIntegrationEnabled: (Boolean) -> Unit,
    val setNotificationsEnabled: (Boolean) -> Unit,
    val setLauncherManagement: (Boolean) -> Unit,
    val requestOverlayPermission: () -> Unit,
    val clearProfileError: () -> Unit,
    val saveHomeControls: (List<HaControlSpec>, (Result<Unit>) -> Unit) -> Unit,
    val openAndroidSettings: () -> Unit,
    val importSetup: () -> Unit,
    val dismissMessage: () -> Unit,
    val showMessage: (String) -> Unit,
    val openRecoverySettings: () -> Unit,
    val requestContinueAccess: () -> Unit,
    val openProjectLink: (String) -> Unit,
)
