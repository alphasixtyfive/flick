package app.flick.homeassistant

import org.junit.Assert.*
import org.junit.Test

class HaControlFeedbackTest {
    private val off = HaControl("light.example", "Example", "light", state = "off")

    @Test fun requestMovesTheSwitchWithoutChangingReportedState() {
        val requested = off.withRequest(true, targetOn = true)
        assertTrue(requested.displayedOn == true)
        assertEquals("off", requested.state)
        assertTrue(requested.pending)
    }

    @Test fun confirmedEventBeforeRequestFinishesCannotFlipTheSwitchBack() {
        val requested = off.withRequest(true, targetOn = true)
        val reported = requested.copy(state = "on")
        assertTrue(reported.displayedOn == true)
        assertTrue(reported.withRequest(false).displayedOn == true)
    }

    @Test fun failedOrTimedOutRequestRestoresTheReportedPosition() {
        val failed = off.withRequest(true, targetOn = true).withRequest(false, error = "Not confirmed")
        assertFalse(failed.displayedOn == true)
        assertEquals("off", failed.state)
        assertNull(failed.requestedOn)
        assertFalse(failed.pending)
        assertEquals("Not confirmed", failed.error)
    }

    @Test fun externalChangesUseReportedStateAfterTheRequestEnds() {
        val changed = off.copy(state = "on").withRequest(true, targetOn = false)
        assertFalse(changed.displayedOn == true)
        val external = changed.copy(state = "on")
        assertTrue(external.withRequest(false).displayedOn == true)
        assertTrue(off.copy(state = "on").displayedOn == true)
    }

    @Test fun unknownOrUnavailableStateNeverBecomesAnInventedSwitchState() {
        for (state in listOf(null, "unknown", "unavailable")) {
            val control = off.copy(state = state).withRequest(true, targetOn = true)
            assertNull(control.displayedOn)
            assertEquals(state, control.state)
        }
    }

    @Test fun sceneRequestsNeverAcquireAPowerTarget() {
        val scene = HaControl("scene.example", "Example", "scene").withRequest(true, targetOn = true)
        assertTrue(scene.pending)
        assertNull(scene.requestedOn)
        assertNull(scene.displayedOn)
        assertFalse(scene.withRequest(false).pending)
    }

    @Test fun finishingARequestClearsItsTargetEvenWhenAlreadyConfirmed() {
        val confirmed = off.withRequest(true, targetOn = true).copy(state = "on").withRequest(false)
        assertEquals("on", confirmed.state)
        assertNull(confirmed.requestedOn)
        assertFalse(confirmed.pending)
    }
}
