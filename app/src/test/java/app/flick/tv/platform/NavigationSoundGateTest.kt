package app.flick.tv.platform

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class NavigationSoundGateTest {
    @Test fun rapidEventsDoNotLayerTheShortSound() {
        val gate = NavigationSoundGate()
        assertTrue(gate.accept(0))
        assertFalse(gate.accept(10))
        assertFalse(gate.accept(64))
        assertTrue(gate.accept(65))
    }

    @Test fun choosingANewModePreviewsItEvenAfterTheSelectionKeySound() {
        val gate = NavigationSoundGate()
        assertTrue(gate.accept(100))
        assertTrue(gate.accept(110, preview = true))
        assertFalse(gate.accept(111))
        assertTrue(gate.accept(175))
    }

    @Test fun nativeAndComposeWindowsOnlySoundForInitialNavigationInput() {
        assertTrue(isNavigationFeedback(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, false))
        assertTrue(isNavigationFeedback(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, 0, false))
        assertFalse(isNavigationFeedback(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0, false))
        assertFalse(isNavigationFeedback(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1, false))
        assertFalse(isNavigationFeedback(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, true))
        assertFalse(isNavigationFeedback(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0, false))
    }
}
