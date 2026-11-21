package app.flick.tv.wallpaper

import app.flick.tv.profiles.Profile
import app.flick.tv.settings.LauncherSettings
import app.flick.tv.settings.SettingsCodec
import org.junit.Assert.*
import org.junit.Test

class BuiltInWallpapersTest {
    @Test fun includedWallpapersHaveDistinctStableNamesAndResourceUris() {
        assertEquals(9, BuiltInWallpapers.items.size)
        assertEquals(9, BuiltInWallpapers.items.map { it.id }.distinct().size)
        assertEquals(9, BuiltInWallpapers.items.map { it.uri }.distinct().size)
        assertTrue(BuiltInWallpapers.items.all { it.title.isNotBlank() && it.drawable != 0 })
        assertEquals("android.resource://app.flick.tv/drawable/flick_petal_nocturne",
            BuiltInWallpapers.items.first { it.id == "petal-nocturne" }.uri)
    }

    @Test fun legacyBuiltInWallpaperResolvesToTheNewPackageUri() {
        val legacy = "android.resource://app.flick/drawable/flick_petal_nocturne"
        val current = "android.resource://app.flick.tv/drawable/flick_petal_nocturne"
        assertEquals(current, BuiltInWallpapers.find(legacy)?.uri)
        val settings = LauncherSettings(profiles = listOf(Profile("home", "Home", wallpaperUri = legacy)))
        assertEquals(current, SettingsCodec.readStored(SettingsCodec.write(settings)).profiles.single().wallpaperUri)
    }

    @Test fun resourceAccessIsLimitedToTheIncludedWallpapers() {
        for (wallpaper in BuiltInWallpapers.items) assertTrue(validWallpaperPhotoUri(wallpaper.uri))
        for (uri in listOf(
            "android.resource://another.app/drawable/flick_petal_nocturne",
            "android.resource://app.flick/raw/private",
            "android.resource://app.flick/drawable/not_included",
            "android.resource://app.flick/1234",
            BuiltInWallpapers.items.first().uri + "?other=true",
            BuiltInWallpapers.items.first().uri + "#fragment",
            "file:///data/private/image.jpg",
        )) assertFalse(uri, validWallpaperPhotoUri(uri))
    }

    @Test fun resourceUrisCannotBeSubmittedAsRemotePhotoUrls() {
        for (wallpaper in BuiltInWallpapers.items) assertFalse(validOnlinePhotoUrl(wallpaper.uri))
        assertTrue(validWallpaperPhotoUri("https://images.example.test/scene.jpg"))
        assertTrue(validWallpaperPhotoUri("content://photos.example.test/document/42"))
        assertFalse(validWallpaperPhotoUri("https://name:password@images.example.test/scene.jpg"))
    }

    @Test fun aSelectedBuiltInProfileWallpaperRoundTripsAcrossUpdates() {
        val selected = BuiltInWallpapers.items.first { it.id == "petal-nocturne" }
        val settings = LauncherSettings(profiles = listOf(Profile("home", "Home", wallpaperUri = selected.uri)))
        assertEquals(settings, SettingsCodec.readStored(SettingsCodec.write(settings)))
        assertEquals(selected.title, photoSourceLabel(selected.uri))
    }

    @Test fun freshInstallHasAnOfflineWallpaperWithoutAFileGrant() {
        val initial = BuiltInWallpapers.initialSettings()
        assertNull(initial.folderUri)
        assertEquals(1, initial.photos.size)
        assertEquals("midnight-ribbon", BuiltInWallpapers.find(initial.photos.single().uri)?.id)
    }
}
