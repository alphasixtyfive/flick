package app.flick.tv.themes

import app.flick.tv.profiles.Profile
import app.flick.tv.settings.LauncherSettings
import app.flick.tv.settings.SettingsCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThemeSettingsTest {
    private val adult = Profile("adult", "Adult", pinSalt = "a".repeat(32), pinHash = "b".repeat(64))
    private val child = Profile("child", "Child", child = true, allowedApps = listOf("org.example.video"))

    @Test fun themesSurviveSettingsRoundTripAlongsideProfiles() {
        val appearance = ThemeCatalog.addImported(AppearanceSettings(), ThemeCatalog.warm.copy(name = "My Warm"))
        val settings = LauncherSettings(profiles = listOf(adult, child), activeProfileId = child.id, appearance = appearance)
        assertEquals(settings, SettingsCodec.readStored(SettingsCodec.write(settings)))
    }

    @Test fun brokenAppearanceDoesNotDiscardProfilesOrProtection() {
        val settings = LauncherSettings(profiles = listOf(adult, child), activeProfileId = child.id)
        val json = SettingsCodec.write(settings).put("appearance", JSONObject().put("version", 99))
        val restored = SettingsCodec.readStored(json)
        assertEquals(settings, restored)
        json.getJSONArray("profiles").getJSONObject(0).remove("pinHash")
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.readStored(json) }
    }

    @Test fun staleThemeChangesCannotRestoreOldProfileAuthority() {
        val before = LauncherSettings(profiles = listOf(adult, child))
        val current = before.copy(activeProfileId = child.id, showWeather = false)
        val requested = before.copy(appearance = AppearanceSettings("warm"))
        assertEquals(current.copy(appearance = requested.appearance), SettingsCodec.mergePresentation(current, before, requested))
    }

    @Test fun unrelatedEditsDoNotOverwriteAConcurrentThemeChoice() {
        val before = LauncherSettings()
        val current = before.copy(appearance = AppearanceSettings("graphite"))
        val requested = before.copy(clock24 = false)
        assertEquals(current.copy(clock24 = false), SettingsCodec.mergePresentation(current, before, requested))
    }
}
