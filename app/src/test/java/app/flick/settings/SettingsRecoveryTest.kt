package app.flick.settings

import app.flick.home.HomePolicy
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
}
