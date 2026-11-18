package app.flick.home

import androidx.compose.runtime.Composable
import app.flick.profiles.Profile
import app.flick.profiles.ProfileSession
import app.flick.weather.WeatherLocationPanel
import app.flick.wallpaper.WallpaperSettingsPanel
import app.flick.profiles.ProfileManagementPanel
import app.flick.profiles.ProfileStartupPanel
import app.flick.homeassistant.HomeDisplayPowerPicker
import app.flick.ui.components.TvPanel
import androidx.tv.material3.Text
import app.flick.profiles.ProfileEditor
import app.flick.homeassistant.HomeAssistantSetupPanel
import app.flick.homeassistant.HomeEntityPicker
import app.flick.homeassistant.HomeAssistantControlsPanel
import app.flick.integration.IntegrationPanel
import app.flick.updates.UpdatePanel
import app.flick.hometext.HomeTextPanel

@Composable
internal fun HomePanels(panel: HomePanel?, state: HomeState, actions: HomeActions,
    onClose: () -> Unit, onPanel: (HomePanel) -> Unit) {
    when (panel) {
        HomePanel.Theme -> app.flick.themes.ThemePanel(state.settings.appearance, actions.selectTheme,
            actions.importTheme, actions.exportTheme, actions.removeTheme, onBack = { onPanel(HomePanel.Settings) })
        HomePanel.Backup -> app.flick.backup.BackupPanel(state.backup, actions.prepareBackup, actions.saveBackup,
            actions.chooseBackup, actions.restoreBackup, actions.discardBackup, onBack = { onPanel(HomePanel.Settings) })
        HomePanel.ManageProfiles -> ProfileManagementPanel(state.settings.profiles, state.settings.activeProfileId,
            onEdit = { onPanel(HomePanel.EditProfile(it)) },
            onAdd = { onPanel(HomePanel.EditProfile(Profile(java.util.UUID.randomUUID().toString(), ""))) },
            onBack = { onPanel(HomePanel.Settings) }, startup = ProfileSession.startup(state.settings),
            onStartup = { onPanel(HomePanel.ProfileStartup) })
        HomePanel.ProfileStartup -> ProfileStartupPanel(ProfileSession.startup(state.settings),
            onStartupChange = { actions.updateSettings(state.settings, state.settings.copy(profileStartup = it)) },
            standbySource = state.haConfiguration.displayPowerEntity?.name, homeAssistantConfigured = state.haConfiguration.configured,
            onStandby = { onPanel(HomePanel.TvStandby) }, onBack = { onPanel(HomePanel.ManageProfiles) })
        HomePanel.TvStandby -> if (state.haConfiguration.configured) HomeDisplayPowerPicker(
            state.haConfiguration.displayPowerEntity, actions.discoverDisplayPlayers, actions.saveDisplayPowerEntity,
            onBack = { onPanel(HomePanel.ProfileStartup) })
        else TvPanel("TV standby detection", onBack = { onPanel(HomePanel.ProfileStartup) }) {
            Text("Connect Home Assistant before changing the optional TV source. Android screen-off detection still works.")
        }
        is HomePanel.EditProfile -> ProfileEditor(panel.profile, state.settings.profiles, state.apps, ProfileSession.startup(state.settings),
            actions.saveProfile, actions.deleteProfile, actions.setProfileApp, actions.chooseProfilePhoto, actions.removeProfilePhoto,
            actions.chooseProfileWallpaper, actions.removeProfileWallpaper,
            actions.setProfileWallpaper,
            onBack = { onPanel(HomePanel.ManageProfiles) })
        HomePanel.HomeAssistantControls -> HomeAssistantControlsPanel(state.homeControls, state.haConnectionState,
            actions.setHomePower, actions.runHomeScene, actions.openHomeCamera, actions.retryHomeAssistant,
            onChooseControls = { actions.discoverHomeEntities(); onPanel(HomePanel.HomeEntities(fromControls = true)) },
            onBack = onClose)
        HomePanel.Weather -> WeatherLocationPanel(state.settings.weatherLocation, state.places, state.searchingPlaces,
            actions.searchPlaces, { actions.updateSettings(state.settings, state.settings.copy(weatherLocation = it)) }, onBack = { onPanel(HomePanel.Settings) })
        HomePanel.Wallpaper -> WallpaperSettingsPanel(state.wallpaperSettings,
            { requested, complete -> actions.saveWallpaperSettings(state.wallpaperSettings, requested, complete) }, actions.rotateWallpaper,
            actions.chooseWallpaperFolder, actions.chooseWallpaperPhotos, onBack = { onPanel(HomePanel.Settings) })
        HomePanel.HomeAssistantSetup -> HomeAssistantSetupPanel(state.haConfiguration, state.haConnectionState, state.haBusy, actions.configureHomeAssistant, actions.retryHomeAssistant, onBack = { onPanel(HomePanel.Settings) },
            onChooseControls = { actions.discoverHomeEntities(); onPanel(HomePanel.HomeEntities()) },
            onIntegration = { onPanel(HomePanel.Integration) }, onHomeText = { onPanel(HomePanel.HomeText) },
            connectionDetail = when {
                !state.bridge.paired -> "Not paired"
                !state.bridge.enabled -> "Paused. Pairing is saved."
                else -> state.bridge.connection.label
            })
        HomePanel.HomeText -> HomeTextPanel(state, actions,
            onIntegration = { onPanel(HomePanel.Integration) }, onBack = { onPanel(HomePanel.HomeAssistantSetup) })
        HomePanel.Integration -> IntegrationPanel(state, actions, onBack = { onPanel(HomePanel.HomeAssistantSetup) })
        HomePanel.Updates -> UpdatePanel(state.update, actions.checkUpdates, actions.downloadUpdate,
            actions.installUpdate, actions.cancelUpdate, onClose = { actions.cancelUpdate(); onPanel(HomePanel.Settings) })
        is HomePanel.HomeEntities -> HomeEntityPicker(state.haEntities, state.haConfiguration.controls, state.haBusy,
            state.haDiscoveryLoaded, actions.setHomeControl, actions.discoverHomeEntities, actions.showMessage,
            onBack = { onPanel(if (panel.fromControls) HomePanel.HomeAssistantControls else HomePanel.HomeAssistantSetup) })
        else -> Unit
    }
}
