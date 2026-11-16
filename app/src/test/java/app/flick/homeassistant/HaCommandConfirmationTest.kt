package app.flick.homeassistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HaCommandConfirmationTest {
    private val on = HaControl("light.example", "Example", "light", state = "on")
    private fun matches(control: HaControl) = control.id == on.id && control.state == "on"

    @Test fun websocketConfirmationInterruptsASlowGetAfterPostAlreadyAcknowledged() = runTest {
        val events = MutableSharedFlow<HaControl>()
        var pollCancelled = false
        val result = async {
            HaCommandConfirmation.await(events, ::matches, send = {}, readConfirmed = {
                try { delay(5000); false } finally { pollCancelled = true }
            })
        }
        runCurrent()
        advanceTimeBy(10)
        events.emit(on)
        runCurrent()
        assertTrue(result.isCompleted)
        assertTrue(pollCancelled)
        assertEquals(10L, testScheduler.currentTime)
        result.await()
    }

    @Test fun acknowledgementAloneCannotConfirmOrChangeState() = runTest {
        val events = MutableSharedFlow<HaControl>()
        var reads = 0
        val result = async {
            HaCommandConfirmation.await(events, ::matches, send = {}, readConfirmed = { reads++; false })
        }
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(reads > 1)
        assertFalse(result.isCompleted)
        result.cancelAndJoin()
    }

    @Test fun pollingCanConfirmWhenNoWebsocketEventArrives() = runTest {
        val events = MutableSharedFlow<HaControl>()
        var reads = 0
        val result = async {
            HaCommandConfirmation.await(events, ::matches, send = {}, readConfirmed = { ++reads == 2 })
        }
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(200)
        runCurrent()
        result.await()
        assertEquals(2, reads)
        assertEquals(200L, testScheduler.currentTime)
    }
}
