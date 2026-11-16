package app.flick.home

import app.flick.apps.AppEntry
import app.flick.apps.ContinueItem
import app.flick.profiles.Profile
import app.flick.settings.LauncherSettings
import org.junit.Assert.*
import org.junit.Test

class HomePolicyTest {
    private val apps = listOf(AppEntry("org.example.video", "Video", null), AppEntry("org.example.browser", "Browser", null))

    @Test fun aChildHomeShowsOnlyExplicitlyAllowedPackages() {
        val settings = LauncherSettings(profiles = listOf(Profile("child", "Child", true, allowedApps = listOf("org.example.video"))), activeProfileId = "child")
        assertEquals(listOf(apps[0]), HomePolicy.visibleApps(settings, apps))
        assertFalse(HomePolicy.canManage(settings))
    }

    @Test fun anUnknownActiveProfileFailsClosed() {
        val settings = LauncherSettings(activeProfileId = "missing")
        assertTrue(HomePolicy.visibleApps(settings, apps).isEmpty())
        assertFalse(HomePolicy.canManage(settings))
    }

    @Test fun continueRemovalSurvivesProviderIdChanges() {
        val item = ContinueItem(1, "org.example.video", "Example Show", null, "")
        assertEquals(HomePolicy.continueKey(item), HomePolicy.continueKey(item.copy(id = 900)))
        assertNotEquals(HomePolicy.continueKey(item), HomePolicy.continueKey(item.copy(packageName = "org.example.other")))
    }

    @Test fun movingAnAppSkipsUninstalledFavorites() {
        assertEquals(listOf("second", "first"), HomePolicy.moveFavorite(
            listOf("first", "removed", "second"), setOf("first", "second"), "first", 1))
    }

    @Test fun movingAtTheEdgeOrAfterUninstallKeepsTheRemainingOrder() {
        assertEquals(listOf("first", "second"), HomePolicy.moveFavorite(
            listOf("first", "second"), setOf("first", "second"), "first", -1))
        assertEquals(listOf("second"), HomePolicy.moveFavorite(
            listOf("first", "second"), setOf("second"), "first", 1))
    }
}
