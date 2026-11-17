package app.flick.hometext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeTextSubscriptionTest {
    private class Connection {
        var nextId = 10
        val unsubscribed = mutableListOf<Int>()
        val requests = mutableListOf<HomeTextContext>()
        var state = HomeTextState()
        val subscription = HomeTextSubscription(
            subscribe = { requests.add(it); nextId++ },
            unsubscribe = { unsubscribed.add(it) }, publish = { state = it },
        )
        fun context(id: String = "adult", child: Boolean = false, generation: Long = 1) =
            HomeTextContext(HomeTextProfile(id, child, 0), generation)
        fun result(id: Int, success: Boolean = true, code: String = "unknown_command") =
            JSONObject().put("id", id).put("type", "result").put("success", success)
                .put("error", JSONObject().put("code", code))
        fun event(id: Int, profile: String = "adult", generation: Long = 1, revision: Long = 1, text: String = "Ready") =
            JSONObject().put("id", id).put("type", "event").put("event", JSONObject()
                .put("profile_id", profile).put("profile_generation", generation).put("revision", revision)
                .put("text", text).put("show_for_children", false))
    }

    @Test fun optionalUnknownCommandDoesNotConsumeMainSubscriptionResults() {
        val connection = Connection()
        assertEquals(10, connection.subscription.update(connection.context()))
        assertFalse(connection.subscription.handle(connection.result(1)))
        assertTrue(connection.subscription.handle(connection.result(10, false)))
        assertEquals(HomeTextStatus.Unsupported, connection.state.status)
        connection.subscription.timeout(10)
        assertEquals(HomeTextStatus.Unsupported, connection.state.status)
        assertNull(connection.state.snapshot)
    }

    @Test fun eventsRequireAcknowledgementAndStrictlyIncreasingRevisions() {
        val connection = Connection()
        connection.subscription.update(connection.context())
        connection.subscription.handle(connection.event(10))
        assertNull(connection.state.snapshot)
        connection.subscription.handle(connection.result(10))
        connection.subscription.handle(connection.event(10, revision = 2))
        assertEquals("Ready", connection.state.snapshot?.text)
        connection.subscription.handle(connection.event(10, revision = 1, text = "Old"))
        connection.subscription.handle(connection.event(10, revision = 2, text = "Duplicate"))
        assertEquals("Ready", connection.state.snapshot?.text)
        connection.subscription.handle(connection.event(10, revision = 3, text = ""))
        assertEquals("", connection.state.snapshot?.text)
    }

    @Test fun unexpectedPositionedFieldsClearTheOldMessageAndAValidUpdateRecovers() {
        val connection = Connection()
        connection.subscription.update(connection.context())
        connection.subscription.handle(connection.result(10))
        connection.subscription.handle(connection.event(10))
        connection.subscription.handle(connection.event(10, revision = 2).apply { getJSONObject("event").put("left", "Unexpected") })
        assertEquals(HomeTextStatus.Unavailable, connection.state.status)
        assertNull(connection.state.snapshot)
        connection.subscription.handle(connection.event(10, revision = 3, text = "Recovered"))
        assertEquals("Recovered", connection.state.snapshot?.text)
    }

    @Test fun leavingAndReturningToAdultRejectsBothOldIdAndOldGeneration() {
        val connection = Connection()
        connection.subscription.update(connection.context())
        connection.subscription.handle(connection.result(10))
        connection.subscription.handle(connection.event(10))
        connection.subscription.update(connection.context("child", true, 2))
        assertNull(connection.state.snapshot)
        connection.subscription.update(connection.context(generation = 3))
        assertEquals(listOf(10, 11), connection.unsubscribed)
        connection.subscription.handle(connection.result(12))
        assertFalse(connection.subscription.handle(connection.event(10, revision = 99)))
        connection.subscription.handle(connection.event(12, generation = 1, revision = 99))
        assertNull(connection.state.snapshot)
        connection.subscription.handle(connection.event(12, generation = 3))
        assertEquals("Ready", connection.state.snapshot?.text)
    }

    @Test fun lockingUnsubscribesAndIgnoresQueuedEvents() {
        val connection = Connection()
        connection.subscription.update(connection.context())
        connection.subscription.handle(connection.result(10))
        connection.subscription.handle(connection.event(10))
        connection.subscription.update(null)
        assertNull(connection.state.snapshot)
        assertEquals(listOf(10), connection.unsubscribed)
        assertFalse(connection.subscription.handle(connection.event(10, revision = 2)))
        assertNull(connection.state.snapshot)
    }

    @Test fun invalidSnapshotClearsTheMessageButFutureValidUpdateCanRecover() {
        val connection = Connection()
        connection.subscription.update(connection.context())
        connection.subscription.handle(connection.result(10))
        connection.subscription.handle(connection.event(10))
        connection.subscription.handle(connection.event(10, revision = 2, text = "x".repeat(121)))
        assertEquals(HomeTextStatus.Unavailable, connection.state.status)
        assertNull(connection.state.snapshot)
        connection.subscription.handle(connection.event(10, revision = 3, text = "Recovered"))
        assertEquals("Recovered", connection.state.snapshot?.text)
    }

    @Test fun timeoutAndDisconnectRemoveTheOldSubscription() {
        val connection = Connection()
        connection.subscription.update(connection.context())
        connection.subscription.timeout(10)
        assertEquals(HomeTextStatus.Unavailable, connection.state.status)
        assertEquals(listOf(10), connection.unsubscribed)
        assertFalse(connection.subscription.handle(connection.result(10)))
        connection.subscription.update(null)
        connection.subscription.update(connection.context(generation = 2))
        connection.subscription.handle(connection.result(11))
        connection.subscription.handle(connection.event(11, generation = 2))
        connection.subscription.close()
        assertNull(connection.state.snapshot)
        assertFalse(connection.subscription.handle(connection.event(11, generation = 2, revision = 2)))
    }
}
