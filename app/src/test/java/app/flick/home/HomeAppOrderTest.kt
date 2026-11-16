package app.flick.home

import app.flick.apps.AppEntry
import app.flick.profiles.Profile
import app.flick.profiles.ProfileSession
import app.flick.settings.LauncherSettings
import org.junit.Assert.*
import org.junit.Test

class HomeAppOrderTest {
    private val first = "org.example.first"
    private val second = "org.example.second"
    private val third = "org.example.third"
    private val apps = listOf(first, second, third).map { AppEntry(it, it, null) }
    private val adult = Profile("parent", "Parent", appOrder = listOf(second, first))
    private val child = Profile("child", "Child", child = true, allowedApps = listOf(first, second))
    private val settings = LauncherSettings(profiles = listOf(adult, child), activeProfileId = child.id,
        favorites = listOf(first, second), showWeather = false)

    @Test fun existingLayoutsKeepTheirOrderUntilThatProfileRearrangesThem() {
        assertEquals(listOf(first, second), HomePolicy.homeAppPackages(settings, apps))
        val legacyAdult = settings.copy(profiles = listOf(adult.copy(appOrder = emptyList()), child),
            activeProfileId = adult.id, favorites = listOf(second, first))
        assertEquals(listOf(second, first), HomePolicy.homeAppPackages(legacyAdult, apps))
    }

    @Test fun eachProfileRearrangesOnlyItsOwnHome() {
        val updated = HomePolicy.reorderApps(settings, child.id, listOf(second, first), apps)
        assertEquals(settings.copy(profiles = listOf(adult, child.copy(appOrder = listOf(second, first)))), updated)
        assertEquals(listOf(second, first), HomePolicy.homeAppPackages(updated, apps))
        val parentSettings = updated.copy(activeProfileId = adult.id)
        val parentUpdated = HomePolicy.reorderApps(parentSettings, adult.id, listOf(first, second), apps)
        assertEquals(listOf(first, second), HomePolicy.homeAppPackages(parentUpdated, apps))
        assertEquals(updated.profiles[1], parentUpdated.profiles[1])
        assertEquals(settings.favorites, parentUpdated.favorites)
    }

    @Test fun anOrderCannotApproveAppsOrChangeAnyOtherPermission() {
        val updated = HomePolicy.reorderApps(settings, child.id, listOf(third, second, first), apps)
        assertEquals(listOf(second, first), updated.profiles[1].appOrder)
        assertEquals(child.allowedApps, updated.profiles[1].allowedApps)
        val session = ProfileSession(updated)
        assertTrue(session.authorize(updated, updated.profiles[1], session.state.value.generation))
        assertTrue(session.canLaunch(updated, first))
        assertFalse(session.canLaunch(updated, third))
        assertFalse(HomePolicy.canManage(updated))
        val injected = settings.copy(profiles = listOf(adult, child.copy(appOrder = listOf(third, second))))
        assertEquals(listOf(second, first), HomePolicy.homeAppPackages(injected, apps))
    }

    @Test fun permissionChangesDuringAMoveUseTheLatestGrants() {
        val allowedThird = child.copy(allowedApps = listOf(first, third))
        val latest = settings.copy(profiles = listOf(adult, allowedThird))
        val updated = HomePolicy.reorderApps(latest, child.id, listOf(second, first), apps)
        assertEquals(listOf(first, third), updated.profiles[1].appOrder)
        assertEquals(allowedThird.allowedApps, updated.profiles[1].allowedApps)
        val session = ProfileSession(updated)
        assertTrue(session.authorize(updated, updated.profiles[1], session.state.value.generation))
        assertTrue(session.canLaunch(updated, first))
        assertFalse(session.canLaunch(updated, second))
    }

    @Test fun removedAppsNeverReturnThroughAnOldOrderAndNewApprovalsAppend() {
        val saved = settings.copy(profiles = listOf(adult, child.copy(appOrder = listOf(second, first))))
        assertEquals(listOf(first), HomePolicy.homeAppPackages(saved, apps.filterNot { it.packageName == second }))
        val newApproval = saved.copy(profiles = listOf(adult, saved.profiles[1].copy(allowedApps = listOf(first, second, third))))
        assertEquals(listOf(second, first, third), HomePolicy.homeAppPackages(newApproval, apps))
    }

    @Test fun staleOrMalformedRequestsCannotEditOtherProfiles() {
        assertThrows(IllegalArgumentException::class.java) { HomePolicy.reorderApps(settings, adult.id, listOf(second), apps) }
        assertThrows(IllegalArgumentException::class.java) { HomePolicy.reorderApps(settings, "missing", listOf(first), apps) }
        assertThrows(IllegalArgumentException::class.java) { HomePolicy.reorderApps(settings, child.id, listOf("intent://payload"), apps) }
        assertThrows(IllegalArgumentException::class.java) { HomePolicy.reorderApps(settings, child.id, List(101) { first }, apps) }
        assertEquals(listOf(second, first), HomePolicy.reorderApps(settings, child.id,
            listOf(second, second, first), apps).profiles[1].appOrder)
    }
}
