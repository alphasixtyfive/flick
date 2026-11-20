package app.flick.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePressTest {
    @Test fun shortPressClicksOnlyOnItsMatchingRelease() {
        val press = RemotePress()
        assertNotNull(press.down(23, 0))
        assertEquals(RemotePress.Release.Ignored, press.up(66))
        assertEquals(RemotePress.Release.Click, press.up(23))
        assertEquals(RemotePress.Release.Ignored, press.up(23))
    }

    @Test fun holdingOpensOnceBeforeReleaseWithoutARepeatEvent() {
        val press = RemotePress()
        val token = press.down(23, 0)!!
        assertTrue(press.longPress(token))
        assertFalse(press.longPress(token))
        assertEquals(RemotePress.Release.Consumed, press.up(23))
    }

    @Test fun newPopupIgnoresTheButtonThatOpenedIt() {
        val original = RemotePress()
        assertTrue(original.longPress(original.down(23, 0)!!))
        original.cancel()
        val popup = RemotePress()
        assertNull(popup.down(23, 1))
        assertNull(popup.down(23, 2))
        assertEquals(RemotePress.Release.Ignored, popup.up(23))
        assertNotNull(popup.down(23, 0))
        assertEquals(RemotePress.Release.Click, popup.up(23))
    }

    @Test fun repeatsAndDuplicateDownDoNotRestartTheHold() {
        val press = RemotePress()
        val token = press.down(23, 0)!!
        assertNull(press.down(23, 0))
        assertNull(press.down(23, 1))
        assertNull(press.down(23, 8))
        assertTrue(press.longPress(token))
        assertEquals(RemotePress.Release.Consumed, press.up(23))
    }

    @Test fun focusLossOrPauseCancelsTimeoutAndRelease() {
        val press = RemotePress()
        val token = press.down(23, 0)!!
        press.cancel()
        assertFalse(press.longPress(token))
        assertEquals(RemotePress.Release.Ignored, press.up(23))
        assertEquals(RemotePress.Release.Click, press.run { down(23, 0); up(23) })
    }

    @Test fun cancelledNativeReleaseNeverClicks() {
        val press = RemotePress()
        val token = press.down(23, 0)!!
        assertEquals(RemotePress.Release.Consumed, press.up(23, cancelled = true))
        assertFalse(press.longPress(token))
    }

    @Test fun oldTimeoutCannotOpenMenuForANewerPress() {
        val press = RemotePress()
        val oldToken = press.down(23, 0)!!
        press.up(23)
        val token = press.down(23, 0)!!
        assertFalse(press.longPress(oldToken))
        assertTrue(press.longPress(token))
    }

    @Test fun overlappingButtonCannotStealTheGesture() {
        val press = RemotePress()
        assertNotNull(press.down(23, 0))
        assertNull(press.down(66, 0))
        assertEquals(RemotePress.Release.Ignored, press.up(66))
        assertEquals(RemotePress.Release.Click, press.up(23))
    }

    @Test fun dialogHandoffRetiresTheOriginalGestureWithoutARelease() {
        val original = RemotePress()
        assertTrue(original.longPress(original.down(23, 0)!!))
        original.cancel()
        assertEquals(RemotePress.Release.Ignored, original.up(23))
        assertNotNull(original.down(23, 0))
        assertEquals(RemotePress.Release.Click, original.up(23))
    }

    @Test fun firstClickAfterClosingLongPressMenuWorks() {
        val press = RemotePress()
        assertTrue(press.longPress(press.down(23, 0)!!))
        press.cancel()
        assertNotNull(press.down(23, 0))
        assertEquals(RemotePress.Release.Click, press.up(23))
    }

    @Test fun systemFlagAtTheThresholdConsumesTheHoldBeforeItsTimerRuns() {
        val press = RemotePress()
        val token = press.down(23, 0)!!
        assertTrue(press.longPressForKey(23))
        assertFalse(press.longPress(token))
        assertFalse(press.longPressForKey(23))
        assertEquals(RemotePress.Release.Consumed, press.up(23))
    }

    @Test fun timerAndSystemFlagCanNeverBothOpenTheSameMenu() {
        val press = RemotePress()
        assertTrue(press.longPress(press.down(23, 0)!!))
        assertFalse(press.longPressForKey(23))
        assertEquals(RemotePress.Release.Consumed, press.up(23))
    }

    @Test fun nativeLongPressCannotBeInheritedByAPopupOrADifferentButton() {
        val original = RemotePress()
        original.down(23, 0)
        assertFalse(original.longPressForKey(66))
        assertTrue(original.longPressForKey(23))
        original.cancel()
        val popup = RemotePress()
        assertNull(popup.down(23, 1))
        assertFalse(popup.longPressForKey(23))
        assertEquals(RemotePress.Release.Ignored, popup.up(23))
        assertFalse(original.longPressForKey(23))
        assertNotNull(original.down(23, 0))
        assertEquals(RemotePress.Release.Click, original.up(23))
    }
}
