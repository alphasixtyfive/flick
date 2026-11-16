package app.flick.profiles

import app.flick.settings.LauncherSettings
import org.junit.Assert.*
import org.junit.Test

class ProfilePermissionsTest {
    private val parent = Profile("parent", "Parent", pinSalt = "test-salt", pinHash = "test-hash")
    private val child = Profile("child", "Child", child = true, avatar = "content://photos/avatar",
        wallpaperUri = "content://photos/wallpaper", allowedApps = listOf("org.example.video"))
    private val other = Profile("other", "Other child", child = true)
    private val settings = LauncherSettings(profiles = listOf(parent, child, other), activeProfileId = parent.id)

    @Test fun grantingAnAppOnlyChangesTheTargetChildsPermissions() {
        val updated = settings.withChildApp(child.id, "org.example.game", true)
        assertEquals(settings.copy(profiles = listOf(parent,
            child.copy(allowedApps = listOf("org.example.video", "org.example.game")), other)), updated)
    }

    @Test fun rapidChangesToDifferentAppsMergeInsteadOfReplacingTheWholeList() {
        val updated = settings.withChildApp(child.id, "org.example.game", true)
            .withChildApp(child.id, "org.example.music", true)
        assertEquals(listOf("org.example.video", "org.example.game", "org.example.music"),
            updated.profiles[1].allowedApps)
    }

    @Test fun unrelatedSavedProfileEditsSurviveTheNextAppChange() {
        val renamed = child.copy(name = "Updated name", avatar = "content://photos/new")
        val latest = settings.copy(profiles = listOf(parent, renamed, other))
        assertEquals(renamed.copy(allowedApps = listOf("org.example.video", "org.example.game")),
            latest.withChildApp(child.id, "org.example.game", true).profiles[1])
    }

    @Test fun revokingOneAppKeepsAllOtherPermissions() {
        val updated = settings.withChildApp(child.id, "org.example.game", true)
            .withChildApp(child.id, "org.example.video", false)
        assertEquals(listOf("org.example.game"), updated.profiles[1].allowedApps)
    }

    @Test fun repeatedGrantsAndRevokingAnAbsentAppAreIdempotent() {
        assertEquals(settings, settings.withChildApp(child.id, "org.example.video", true))
        assertEquals(settings, settings.withChildApp(child.id, "org.example.absent", false))
    }

    @Test fun deletingOrChangingTheRoleOfTheTargetCannotResurrectAChildProfile() {
        assertThrows(IllegalStateException::class.java) { settings.withChildApp("missing", "org.example.video", true) }
        assertThrows(IllegalArgumentException::class.java) { settings.withChildApp(parent.id, "org.example.video", true) }
        val latest = settings.copy(profiles = listOf(parent, child.copy(child = false), other))
        assertThrows(IllegalArgumentException::class.java) { latest.withChildApp(child.id, "org.example.video", true) }
    }

    @Test fun theLimitAllowsAnExistingPermissionOrReplacingOnePermission() {
        val full = child.copy(allowedApps = (0 until 100).map { "org.example.app$it" })
        val latest = settings.copy(profiles = listOf(parent, full, other))
        assertEquals(latest, latest.withChildApp(child.id, "org.example.app0", true))
        assertThrows(IllegalArgumentException::class.java) { latest.withChildApp(child.id, "org.example.extra", true) }
        val replaced = latest.withChildApp(child.id, "org.example.app0", false)
            .withChildApp(child.id, "org.example.extra", true)
        assertEquals(100, replaced.profiles[1].allowedApps.size)
        assertFalse("org.example.app0" in replaced.profiles[1].allowedApps)
        assertTrue("org.example.extra" in replaced.profiles[1].allowedApps)
    }

    @Test fun invalidPackageNamesCannotCreateHiddenOrTruncatedPermissions() {
        for (app in listOf("", "single", "org.9invalid", "org.example has spaces", "org." + "a".repeat(260))) {
            assertThrows(IllegalArgumentException::class.java) { settings.withChildApp(child.id, app, true) }
        }
    }

    @Test fun editsToDifferentChildrenKeepBothChanges() {
        val updated = settings.withChildApp(child.id, "org.example.game", true)
            .withChildApp(other.id, "org.example.music", true)
        assertTrue("org.example.game" in updated.profiles[1].allowedApps)
        assertEquals(listOf("org.example.music"), updated.profiles[2].allowedApps)
        assertEquals(parent, updated.profiles[0])
    }
}
