package app.flick.tv.wallpaper

import org.junit.Assert.*
import org.junit.Test

class PhotoSourcePolicyTest {
    @Test fun onlineEntryAcceptsOnlyHttpImagesWithoutEmbeddedCredentials() {
        assertTrue(validOnlinePhotoUrl("https://photos.example.test/family.jpg"))
        assertTrue(validOnlinePhotoUrl("http://nas.example.test/image?id=42"))
        assertFalse(validOnlinePhotoUrl("file:///private/photo.jpg"))
        assertFalse(validOnlinePhotoUrl("javascript:alert(1)"))
        assertFalse(validOnlinePhotoUrl("https://user:password@photos.example.test/family.jpg"))
    }

    @Test fun labelsNeverExposeQueryFragmentOrLogin() {
        val label = photoSourceLabel("https://user:password@photos.example.test/family.jpg?signature=private#hidden")
        assertEquals("family.jpg (photos.example.test)", label)
        assertFalse(label.contains("private"))
        assertFalse(label.contains("password"))
        assertFalse(label.contains("hidden"))
    }

    @Test fun opaqueEndpointsAndDocumentUrisDoNotExposePrivatePaths() {
        assertEquals("photos.example.test", photoSourceLabel("https://photos.example.test/opaque-value?key=hidden"))
        assertEquals("Selected photo", photoSourceLabel("content://private.documents/tree/secret"))
    }
    @Test fun pickerPhotosMergeWithoutDroppingRemoteSourcesOrRepeatingSelection() {
        val remote = WallpaperPhoto("https://photos.example.test/family.jpg")
        val local = WallpaperPhoto("content://photos.documents/document/old")
        val selected = mergeSelectedPhotos(listOf(remote, local), listOf(local.uri, "content://photos.documents/document/new"))
        assertEquals(listOf(remote, local, WallpaperPhoto("content://photos.documents/document/new")), selected)
        assertEquals(selected, mergeSelectedPhotos(selected, emptyList()))
    }

    @Test fun pickerRejectsAnOverfullCollectionWithoutTruncatingIt() {
        val existing = (1..MAX_CONFIGURED_PHOTOS).map { WallpaperPhoto("content://photos.documents/document/$it") }
        assertEquals(existing, mergeSelectedPhotos(existing, listOf(existing.first().uri)))
        assertThrows(IllegalArgumentException::class.java) {
            mergeSelectedPhotos(existing, listOf("content://photos.documents/document/extra"))
        }
    }

    @Test fun pickerAcceptsOnlyDocumentUrisFromTheSystemSelection() {
        listOf("file:///private/photo.jpg", "https://photos.example.test/a.jpg", "content:/missing-authority", "javascript:alert(1)").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { mergeSelectedPhotos(emptyList(), listOf(invalid)) }
        }
    }

    @Test fun slideshowIntervalUsesTheCorrectUnitForShortIntervals() {
        assertEquals("Every 30 seconds", photoIntervalLabel(30))
        assertEquals("Every 1 minute", photoIntervalLabel(60))
        assertEquals("Every 5 minutes", photoIntervalLabel(300))
        assertEquals("Every 1 hour", photoIntervalLabel(3600))
    }
}
