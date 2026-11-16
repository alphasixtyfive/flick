package app.flick.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WallpaperEditsTest {
    @Test fun slideshowEditPreservesPhotosAddedAfterTheScreenRendered() {
        val before = WallpaperSettings(photos = listOf(WallpaperPhoto("https://photos.invalid/one.jpg")))
        val latest = before.copy(photos = before.photos + WallpaperPhoto("https://photos.invalid/two.jpg"), intervalSeconds = 60)
        val edited = mergeWallpaperChanges(latest, before, before.copy(shuffle = false))
        assertEquals(latest.photos, edited.photos)
        assertEquals(60, edited.intervalSeconds)
        assertFalse(edited.shuffle)
    }

    @Test fun removingFolderDoesNotUndoNewerDimmingPreference() {
        val before = WallpaperSettings(folderUri = "content://photos/tree/selected")
        val edited = mergeWallpaperChanges(before.copy(dimAmount = .5f), before, before.copy(folderUri = null))
        assertEquals(null, edited.folderUri)
        assertEquals(.5f, edited.dimAmount)
    }
}
