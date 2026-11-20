package app.flick.tv.wallpaper

import app.flick.tv.home.HomeState
import app.flick.tv.profiles.Profile
import app.flick.tv.profiles.ProfileSecurity
import app.flick.tv.settings.LauncherSettings
import app.flick.tv.settings.SettingsCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileWallpaperTest {
    private val adult = ProfileSecurity.protect(Profile("adult", "Adult"), "1234")
    private val child = Profile("child", "Child", child = true,
        wallpaperUri = "content://example.photos/document/twilight.jpg")
    private val settings = LauncherSettings(profiles = listOf(adult, child), activeProfileId = "adult")

    @Test fun switchingProfilesUsesTheOverrideAndRestoresSharedWallpaper() {
        val home = HomeState(settings = settings.copy(activeProfileId = "adult"), wallpaperUri = "https://example.com/shared.jpg")
        assertEquals("https://example.com/shared.jpg", home.displayedWallpaperUri)
        assertEquals(child.wallpaperUri, home.copy(settings = settings.copy(activeProfileId = "child")).displayedWallpaperUri)
        val switchedBack = home.copy(settings = settings.copy(activeProfileId = "child"))
            .copy(settings = settings.copy(activeProfileId = "adult"))
        assertEquals("https://example.com/shared.jpg", switchedBack.displayedWallpaperUri)
    }

    @Test fun sharedRotationDoesNotReplaceAProfileWallpaper() {
        val home = HomeState(settings = settings.copy(activeProfileId = "child"), wallpaperUri = "https://example.com/one.jpg")
        assertEquals(child.wallpaperUri, home.copy(wallpaperUri = "https://example.com/two.jpg").displayedWallpaperUri)
        val cleared = home.settings.copy(profiles = listOf(adult, child.copy(wallpaperUri = null)))
        assertEquals("https://example.com/one.jpg", home.copy(settings = cleared).displayedWallpaperUri)
    }

    @Test fun storedProfileWallpaperSurvivesRestartWithoutChangingAuthority() {
        val stored = settings.copy(activeProfileId = "child")
        assertEquals(stored, SettingsCodec.readStored(SettingsCodec.write(stored)))
        val oldRecord = SettingsCodec.write(stored)
        oldRecord.getJSONArray("profiles").getJSONObject(1).remove("wallpaperUri")
        assertNull(SettingsCodec.readStored(oldRecord).profiles[1].wallpaperUri)
    }

    @Test fun stalePresentationEditsPreserveProfileWallpaperAndSelection() {
        val before = settings.copy(activeProfileId = "adult")
        val latest = settings.copy(activeProfileId = "child")
        val merged = SettingsCodec.mergePresentation(latest, before, before.copy(showWeather = false))
        assertEquals("child", merged.activeProfileId)
        assertEquals(child.wallpaperUri, merged.profiles[1].wallpaperUri)
        assertFalse(merged.showWeather)
    }

    @Test fun importedWallpapersRejectPrivateFilesAndCredentialUrls() {
        for (uri in listOf("file:///data/private.png", "https://user:secret@example.com/photo.jpg", "content:///missing-authority", "https://example.com\\photo.jpg")) {
            val record = SettingsCodec.write(settings)
            record.getJSONArray("profiles").getJSONObject(1).put("wallpaperUri", uri)
            assertThrows(IllegalArgumentException::class.java) { SettingsCodec.read(JSONObject(record.toString())) }
        }
    }
}
