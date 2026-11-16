package app.flick.settings

import app.flick.home.HomePolicy
import app.flick.profiles.Profile
import app.flick.profiles.ProfileSecurity
import org.junit.Assert.*
import org.junit.Test

class SettingsRecoveryTest {
    @Test fun aNewInstallationCanStartItsFirstSetup() {
        val restored = restoreSettings(null, previouslyInitialized = false)
        assertFalse(restored.requiresRecovery)
        assertTrue(HomePolicy.canManage(restored.settings))
    }

    @Test fun losingTheSettingsKeyCannotCreateAnUnlockedAdult() {
        val restored = restoreSettings(null, previouslyInitialized = true)
        assertTrue(restored.requiresRecovery)
        assertFalse(HomePolicy.canManage(restored.settings))
        assertTrue(restored.settings.profiles.single().child)
        assertTrue(restored.settings.profiles.single().allowedApps.isEmpty())
    }

    @Test fun aValidOlderInstallationDoesNotNeedAnInitializationMarker() {
        val original = LauncherSettings()
        val restored = restoreSettings(SettingsCodec.write(original).toString(), previouslyInitialized = false)
        assertFalse(restored.requiresRecovery)
        assertEquals(original, restored.settings)
    }

    @Test fun damagedOrIncompleteStoredRecordsNeverBecomeDefaultAdultSetup() {
        for (raw in listOf("{", "{}", "{\"profiles\":[]}", "{\"activeProfileId\":\"absent\"}")) {
            val restored = restoreSettings(raw, previouslyInitialized = true)
            assertTrue(restored.requiresRecovery)
            assertFalse(HomePolicy.canManage(restored.settings))
        }
    }

    @Test fun damagedProfileAuthorityEntersRecoveryInsteadOfRemovingRestrictions() {
        val adult = ProfileSecurity.protect(Profile("adult", "Adult"), "0123")
        val family = LauncherSettings(profiles = listOf(adult, Profile("child", "Child", child = true)), activeProfileId = "child")
        val missingRole = SettingsCodec.write(family).apply {
            getJSONArray("profiles").getJSONObject(1).remove("child")
        }
        val malformedRole = SettingsCodec.write(family).apply {
            getJSONArray("profiles").getJSONObject(1).put("child", "unknown")
        }
        val malformedProtection = SettingsCodec.write(LauncherSettings(profiles = listOf(adult), activeProfileId = adult.id)).apply {
            getJSONArray("profiles").getJSONObject(0).put("pinSalt", "broken").put("pinHash", "broken")
        }
        for (damaged in listOf(missingRole, malformedRole, malformedProtection)) {
            val restored = restoreSettings(damaged.toString(), previouslyInitialized = true)
            assertTrue(restored.requiresRecovery)
            assertFalse(HomePolicy.canManage(restored.settings))
            assertTrue(restored.settings.profiles.single().allowedApps.isEmpty())
        }
    }
}
