package app.flick.tv.homeassistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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

    @Test fun websocketConfirmationCanFinishWhilePostIsStillInFlight() = runTest {
        val events = MutableSharedFlow<HaControl>()
        var postCancelled = false
        var reads = 0
        val result = async {
            HaCommandConfirmation.await(events, ::matches, send = {
                try { delay(5000) } finally { postCancelled = true }
            }, readConfirmed = { reads++; false })
        }
        runCurrent()
        advanceTimeBy(10)
        events.emit(on)
        runCurrent()
        result.await()
        assertTrue(postCancelled)
        assertEquals(0, reads)
        assertEquals(10L, testScheduler.currentTime)
    }

    @Test fun unrelatedOrOppositeEventsCannotConfirmTheRequest() = runTest {
        val events = MutableSharedFlow<HaControl>()
        val result = async {
            HaCommandConfirmation.await(events, ::matches, send = {}, readConfirmed = { false })
        }
        runCurrent()
        events.emit(on.copy(id = "light.other"))
        events.emit(on.copy(state = "off"))
        runCurrent()
        assertFalse(result.isCompleted)
        events.emit(on)
        runCurrent()
        result.await()
    }

    @Test fun cancellationStopsThePollingAndEventListener() = runTest {
        val events = MutableSharedFlow<HaControl>()
        var cancelled = false
        val result = async {
            HaCommandConfirmation.await(events, ::matches, send = {}, readConfirmed = {
                try { delay(5000); false } finally { cancelled = true }
            })
        }
        runCurrent()
        result.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(0, events.subscriptionCount.value)
    }

    @Test fun sendFailureStopsTheEventListenerWithoutPolling() = runTest {
        val events = MutableSharedFlow<HaControl>()
        val rejected = IllegalStateException("Request rejected")
        var reads = 0
        val failure = runCatching {
            HaCommandConfirmation.await(events, ::matches, send = { throw rejected }, readConfirmed = {
                reads++
                false
            })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(rejected.message, failure?.message)
        assertEquals(0, reads)
        assertEquals(0, events.subscriptionCount.value)
    }

    @Test fun timeoutStopsTheInFlightReadAndEventListener() = runTest {
        val events = MutableSharedFlow<HaControl>()
        var readCancelled = false
        var reads = 0
        val failure = runCatching {
            withTimeout(6500) {
                HaCommandConfirmation.await(events, ::matches, send = {}, readConfirmed = {
                    reads++
                    try { delay(10000); false } finally { readCancelled = true }
                })
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(readCancelled)
        assertEquals(1, reads)
        assertEquals(0, events.subscriptionCount.value)
        assertEquals(6500L, testScheduler.currentTime)
    }
}
