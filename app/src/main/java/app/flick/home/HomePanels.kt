package app.flick.home

import androidx.compose.runtime.Composable
import app.flick.profiles.Profile
import app.flick.profiles.ProfileSession
import app.flick.weather.WeatherLocationPanel
import app.flick.wallpaper.WallpaperSettingsPanel
import app.flick.profiles.ProfileManagementPanel
import app.flick.profiles.ProfileEditor
import app.flick.homeassistant.HomeAssistantSetupPanel
import app.flick.homeassistant.HomeEntityPicker
import app.flick.homeassistant.HomeAssistantControlsPanel
import app.flick.integration.IntegrationPanel

@Composable
internal fun HomePanels(panel: HomePanel?, state: HomeState, actions: HomeActions,
    onClose: () -> Unit, onPanel: (HomePanel) -> Unit) {
    when (panel) {
        HomePanel.ManageProfiles -> ProfileManagementPanel(state.settings.profiles, state.settings.activeProfileId,
            onEdit = { onPanel(HomePanel.EditProfile(it)) },
            onAdd = { onPanel(HomePanel.EditProfile(Profile(java.util.UUID.randomUUID().toString(), ""))) },
            onBack = { onPanel(HomePanel.Settings) }, startup = ProfileSession.startup(state.settings),
            onStartupChange = { actions.updateSettings(state.settings, state.settings.copy(profileStartup = it)) })
        is HomePanel.EditProfile -> ProfileEditor(panel.profile, state.settings.profiles, state.apps,
            actions.saveProfile, actions.deleteProfile, actions.setProfileApp, actions.chooseProfilePhoto, actions.removeProfilePhoto,
            actions.chooseProfileWallpaper, actions.removeProfileWallpaper,
            actions.setProfileWallpaper,
            onBack = { onPanel(HomePanel.ManageProfiles) })
        HomePanel.HomeAssistantControls -> HomeAssistantControlsPanel(state.homeControls, state.haConnectionState,
            actions.setHomePower, actions.runHomeScene, actions.retryHomeAssistant,
            onChooseControls = { actions.discoverHomeEntities(); onPanel(HomePanel.HomeEntities(fromControls = true)) },
            onBack = onClose)
        HomePanel.Weather -> WeatherLocationPanel(state.settings.weatherLocation, state.places, state.searchingPlaces,
            actions.searchPlaces, { actions.updateSettings(state.settings, state.settings.copy(weatherLocation = it)) }, onBack = { onPanel(HomePanel.Settings) })
        HomePanel.Wallpaper -> WallpaperSettingsPanel(state.wallpaperSettings,
            { requested, complete -> actions.saveWallpaperSettings(state.wallpaperSettings, requested, complete) }, actions.rotateWallpaper,
            actions.chooseWallpaperFolder, actions.chooseWallpaperPhotos, onBack = { onPanel(HomePanel.Settings) })
        HomePanel.HomeAssistantSetup -> HomeAssistantSetupPanel(state.haConfiguration, state.haConnectionState, state.haBusy, actions.configureHomeAssistant, actions.retryHomeAssistant, onBack = { onPanel(HomePanel.Settings) },
            onChooseControls = { actions.discoverHomeEntities(); onPanel(HomePanel.HomeEntities()) },
            onIntegration = { onPanel(HomePanel.Integration) }, displayPower = state.displayPower,
            onDiscoverDisplayPlayers = actions.discoverDisplayPlayers, onSaveDisplayPowerEntity = actions.saveDisplayPowerEntity)
        HomePanel.Integration -> IntegrationPanel(state, actions, onBack = { onPanel(HomePanel.HomeAssistantSetup) })
        is HomePanel.HomeEntities -> HomeEntityPicker(state.haEntities, state.haConfiguration.controls, state.haBusy,
            state.haDiscoveryLoaded, actions.saveHomeControls, actions.showMessage,
            onBack = { onPanel(if (panel.fromControls) HomePanel.HomeAssistantControls else HomePanel.HomeAssistantSetup) })
        else -> Unit
    }
}
