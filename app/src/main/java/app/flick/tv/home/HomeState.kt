package app.flick.tv.home

import app.flick.tv.apps.AppEntry
import app.flick.tv.apps.ContinueItem
import app.flick.tv.homeassistant.HaConfiguration
import app.flick.tv.homeassistant.HaControl
import app.flick.tv.homeassistant.HaConnectionState
import app.flick.tv.homeassistant.HaControlSpec
import app.flick.tv.homeassistant.HaEntity
import app.flick.tv.profiles.Profile
import app.flick.tv.profiles.ProfileSessionState
import app.flick.tv.settings.LauncherSettings
import app.flick.tv.wallpaper.WallpaperSettings
import app.flick.tv.weather.PlaceResult
import app.flick.tv.weather.WeatherReading
import app.flick.tv.integration.BridgeState
import app.flick.tv.hometext.HomeTextState
import app.flick.tv.notifications.NotificationState
import app.flick.tv.BuildConfig
import app.flick.tv.updates.UpdateState
import app.flick.tv.backup.BackupState
import app.flick.tv.themes.ThemeDefinition

/** Immutable screen input. Native repositories remain responsible for authorization and I/O. */
data class HomeState(
    val settings: LauncherSettings = LauncherSettings(),
    val profileSession: ProfileSessionState = ProfileSessionState(),
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
    val homeText: HomeTextState = HomeTextState(),
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
    val update: UpdateState = UpdateState(currentVersion = BuildConfig.VERSION_NAME),
    val backup: BackupState = BackupState(),
) {
    val displayedWallpaperUri: String?
        get() = HomePolicy.profile(settings).wallpaperUri ?: wallpaperUri
}

data class HomeActions(
    val launchApp: (AppEntry) -> Unit,
    val openContinue: (ContinueItem) -> Unit,
    val updateSettings: (LauncherSettings, LauncherSettings) -> Unit,
    val reorderApps: (String, List<String>) -> Unit,
    val appInfo: (AppEntry) -> Unit,
    val uninstallApp: (AppEntry) -> Unit,
    val removeContinue: (ContinueItem) -> Unit,
    val chooseProfile: (Profile, String, (Result<Unit>) -> Unit) -> Unit,
    val cancelProfileSelection: () -> Unit,
    val saveProfile: (Profile, String, (Result<Unit>) -> Unit) -> Unit,
    val setProfileApp: (Profile, String, Boolean, (Result<Unit>) -> Unit) -> Unit,
    val deleteProfile: (Profile, (Result<Unit>) -> Unit) -> Unit,
    val chooseProfilePhoto: (Profile) -> Unit,
    val removeProfilePhoto: (Profile) -> Unit,
    val chooseProfileWallpaper: (Profile) -> Unit,
    val removeProfileWallpaper: (Profile) -> Unit,
    val setProfileWallpaper: (Profile, String) -> Unit,
    val searchPlaces: (String) -> Unit,
    val rotateWallpaper: () -> Unit,
    val saveWallpaperSettings: (WallpaperSettings, WallpaperSettings, (Result<Unit>) -> Unit) -> Unit,
    val chooseWallpaperFolder: () -> Unit,
    val chooseWallpaperPhotos: () -> Unit,
    val setHomePower: (HaControl, Boolean) -> Unit,
    val runHomeScene: (HaControl) -> Unit,
    val openHomeCamera: (String) -> Unit,
    val configureHomeAssistant: (String, String) -> Unit,
    val discoverDisplayPlayers: suspend () -> List<HaEntity>,
    val saveDisplayPowerEntity: suspend (HaControlSpec?) -> Unit,
    val discoverHomeEntities: () -> Unit,
    val retryHomeAssistant: () -> Unit,
    val pairHomeAssistant: () -> Unit,
    val forgetIntegration: () -> Unit,
    val setIntegrationEnabled: (Boolean) -> Unit,
    val setHomeTextEnabled: (Boolean) -> Unit,
    val setNotificationsEnabled: (Boolean) -> Unit,
    val setLauncherManagement: (Boolean) -> Unit,
    val requestOverlayPermission: () -> Unit,
    val clearProfileError: () -> Unit,
    val setHomeControl: (HaControlSpec, Boolean, (Result<Unit>) -> Unit) -> Unit,
    val openAndroidSettings: () -> Unit,
    val openScreensaverSettings: () -> Unit,
    val importTheme: () -> Unit,
    val exportTheme: (ThemeDefinition) -> Unit,
    val selectTheme: (String) -> Unit,
    val removeTheme: (String) -> Unit,
    val prepareBackup: () -> Unit,
    val saveBackup: () -> Unit,
    val chooseBackup: () -> Unit,
    val restoreBackup: (String?, (Result<Unit>) -> Unit) -> Unit,
    val discardBackup: () -> Unit,
    val dismissMessage: () -> Unit,
    val showMessage: (String) -> Unit,
    val openRecoverySettings: () -> Unit,
    val requestContinueAccess: () -> Unit,
    val openProjectLink: (String) -> Unit,
    val checkUpdates: () -> Unit,
    val downloadUpdate: () -> Unit,
    val cancelUpdate: () -> Unit,
    val installUpdate: () -> Unit,
)
