package app.flick.home

import app.flick.apps.AppEntry
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class AppOptionsNavigationTest {
    private val app = AppEntry("org.example.video", "Video", null)

    @Test fun addingOrRemovingAFavoriteFromAllAppsReturnsToTheGrid() {
        val menu = HomePanel.AppOptions(app, fromAllApps = true)
        assertSame(HomePanel.AllApps, appOptionsBackDestination(menu, menu))
    }

    @Test fun aHomeTileMenuReturnsToHome() {
        val menu = HomePanel.AppOptions(app)
        assertNull(appOptionsBackDestination(menu, menu))
    }

    @Test fun aRetiredMenuCannotDismissAProfilePrompt() {
        val menu = HomePanel.AppOptions(app, fromAllApps = true)
        assertSame(HomePanel.Profiles, appOptionsBackDestination(HomePanel.Profiles, menu))
    }

    @Test fun reopeningTheSameAppDoesNotReviveThePreviousMenuCallbacks() {
        val retired = HomePanel.AppOptions(app, fromAllApps = true)
        val current = HomePanel.AppOptions(app, fromAllApps = true)
        assertSame(current, appOptionsBackDestination(current, retired))
    }

    @Test fun aRetiredMenuCannotReopenAllAppsAfterItWasClosed() {
        val menu = HomePanel.AppOptions(app, fromAllApps = true)
        assertNull(appOptionsBackDestination(null, menu))
    }

    @Test fun replacingAMenuRetiresItsActionsEvenWhenTheAppIsUnchanged() {
        val owner = HomePanel.AppOptions(app, fromAllApps = true)
        var current: HomePanel? = owner
        var favorites = emptyList<String>()
        val opened = mutableListOf<String>()
        val add: () -> Unit = {
            runAppOptionsAction({ current === owner }) { favorites = favorites + app.packageName }
        }
        val info: () -> Unit = { runAppOptionsAction({ current === owner }) { opened += "info" } }
        val uninstall: () -> Unit = { runAppOptionsAction({ current === owner }) { opened += "uninstall" } }

        current = HomePanel.AppOptions(app, fromAllApps = true)
        add(); info(); uninstall()

        assertEquals(emptyList<String>(), favorites)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun theCurrentMenuCanFinishItsActionAndReturnToAllApps() {
        val owner = HomePanel.AppOptions(app, fromAllApps = true)
        var current: HomePanel? = owner
        var favorites = emptyList<String>()

        runAppOptionsAction({ current === owner }) {
            favorites = favorites + app.packageName
            current = appOptionsBackDestination(current, owner)
        }

        assertEquals(listOf(app.packageName), favorites)
        assertSame(HomePanel.AllApps, current)
    }
}
