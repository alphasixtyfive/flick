package app.flick.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsNavigationTest {
    @Test fun rootBackClosesInsteadOfNavigatingToRootAgain() {
        assertNull(SettingsPage.Root.parent())
    }

    @Test fun topButtonsReturnToHomeScreenSettings() {
        assertEquals(SettingsPage.Home, SettingsPage.TopControls.parent())
    }

    @Test fun sectionsReturnToRoot() {
        listOf(SettingsPage.Home, SettingsPage.Clock,
            SettingsPage.Device, SettingsPage.About).forEach { assertEquals(SettingsPage.Root, it.parent()) }
    }
}
