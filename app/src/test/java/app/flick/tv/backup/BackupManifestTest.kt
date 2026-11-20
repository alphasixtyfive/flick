package app.flick.tv.backup

import app.flick.tv.profiles.Profile
import app.flick.tv.profiles.ProfileStartup
import app.flick.tv.settings.LauncherSettings
import app.flick.tv.wallpaper.WallpaperPhoto
import app.flick.tv.wallpaper.WallpaperSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupManifestTest {
    private val adult = Profile("adult", "Casey", pinSalt = "a".repeat(32), pinHash = "b".repeat(64))
    private val settings = LauncherSettings(profiles = listOf(adult, Profile("child", "Robin", child = true)),
        activeProfileId = "child", profileStartup = ProfileStartup.Ask)

    @Test fun portableBackupNeverContainsPinVerifiers() {
        val manifest = BackupManifest.create(settings, WallpaperSettings(), { it }, { emptySet() })
        assertFalse(manifest.encoded().contains("pinSalt"))
        assertFalse(manifest.encoded().contains("pinHash"))
        assertFalse(manifest.encoded().contains(adult.pinHash!!))
        assertFalse(manifest.needsParentPin(settings))
        val resolved = manifest.resolve(settings, null, { error("No images expected") }, false)
        assertEquals(adult.pinHash, resolved.settings.profiles.first().pinHash)
    }

    @Test fun unknownAdultsNeedANewPinAndCannotReuseAChildsProtection() {
        val manifest = BackupManifest.create(settings, WallpaperSettings(), { it }, { emptySet() })
        val current = LauncherSettings(profiles = listOf(adult.copy(child = true), Profile("parent", "Parent")), activeProfileId = "parent")
        assertTrue(manifest.needsParentPin(current))
        assertThrows(IllegalArgumentException::class.java) { manifest.resolve(current, null, { it }, false) }
        assertThrows(IllegalArgumentException::class.java) { manifest.resolve(current, "12345", { it }, false) }
    }

    @Test fun connectionCredentialsAreRejectedInsteadOfImported() {
        val manifest = BackupManifest.create(settings, WallpaperSettings(), { it }, { emptySet() })
        val invalid = JSONObject(manifest.encoded())
        invalid.getJSONObject("settings").put("homeAssistant", JSONObject()
            .put("url", "https://example.invalid").put("token", "not-a-real-secret"))
        assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(invalid.toString()) }
    }

    @Test fun profileRolesMustRemainExplicit() {
        val manifest = BackupManifest.create(settings, WallpaperSettings(), { it }, { emptySet() })
        for (role in listOf(null, "adult")) {
            val invalid = JSONObject(manifest.encoded())
            val profile = invalid.getJSONObject("settings").getJSONArray("profiles").getJSONObject(0)
            if (role == null) profile.remove("child") else profile.put("child", role)
            assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(invalid.toString()) }
        }
    }

    @Test fun quotedBracesAndWallpaperOptionsSurviveResolution() {
        val original = settings.copy(profiles = settings.profiles.map { it.copy(name = "A [quiet] {view}") })
        val photos = WallpaperSettings(listOf(WallpaperPhoto("https://example.invalid/image.jpg")),
            intervalSeconds = 450, dimAmount = .3f, shuffle = false)
        val manifest = BackupManifest.create(original, photos, { it }, { emptySet() })
        val restored = BackupManifest.parse(manifest.encoded()).resolve(original, null, { it }, false)
        assertEquals(original, restored.settings)
        assertEquals(photos, restored.wallpaper)
        assertFalse(restored.toString().contains("A [quiet]"))
    }

    @Test fun excessiveJsonDepthIsRejectedBeforeParsing() {
        val nested = "[".repeat(17) + "0" + "]".repeat(17)
        assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(nested) }
    }

    @Test fun folderGrantIsNotAssumedToTransferAndIndividualPhotosSurvive() {
        val photo = "https://example.invalid/photo.jpg"
        val folder = "content://example.documents/tree/family"
        val manifest = BackupManifest.create(settings, WallpaperSettings(listOf(WallpaperPhoto(photo)), folder), { it }, { emptySet() })
        assertTrue(manifest.summary(false).folderNeedsSelection)
        assertNull(manifest.resolve(settings, null, { it }, false).wallpaper?.folderUri)
        assertEquals(folder, manifest.resolve(settings, null, { it }, true).wallpaper?.folderUri)
        assertEquals(photo, manifest.resolve(settings, null, { it }, false).wallpaper?.photos?.single()?.uri)
    }

    @Test fun onlineCredentialsAreNotPortable() {
        assertTrue(BackupManifest.safeOnlineUri("https://example.invalid/photo.jpg"))
        for (url in listOf("https://user:password@example.invalid/photo", "https://example.invalid/photo?token=private",
            "https://example.invalid/photo#private", "file:///private/photo", "https:///missing")) {
            assertFalse(url, BackupManifest.safeOnlineUri(url))
        }
    }

    @Test fun omittedSourcesAreReportedAndEmbeddedReferencesAreComplete() {
        val id = "c".repeat(64)
        val photos = WallpaperSettings(photos = listOf(WallpaperPhoto("content://example/one"), WallpaperPhoto("https://example.invalid/photo?secret=x")))
        val manifest = BackupManifest.create(settings, photos,
            { if (it.startsWith("content:")) BackupManifest.assetReference(id) else null }, { setOf(id) })
        assertEquals(1, manifest.summary(false).photoCount)
        assertEquals(1, manifest.summary(false).embeddedImages)
        assertTrue(manifest.summary(false).notices.any { it.contains("1 online") })
        val invalid = JSONObject(manifest.encoded()).put("assets", org.json.JSONArray())
        assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(invalid.toString()) }
    }

    @Test fun malformedAuthorityAndUnknownFieldsAreRejectedBeforePreview() {
        val manifest = BackupManifest.create(settings, WallpaperSettings(), { it }, { emptySet() })
        val malformed = JSONObject(manifest.encoded())
        malformed.getJSONObject("settings").getJSONArray("profiles").getJSONObject(0).remove("child")
        assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(malformed.toString()) }
        val credentials = JSONObject(manifest.encoded()).put("access_token", "private")
        assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(credentials.toString()) }
        val wrongActive = JSONObject(manifest.encoded())
        wrongActive.getJSONObject("settings").put("activeProfileId", "missing")
        assertThrows(IllegalArgumentException::class.java) { BackupManifest.parse(wrongActive.toString()) }
    }
}
