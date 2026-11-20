package app.flick.tv.integration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeStatusAcksTest {
    private fun result(id: Int, success: Boolean) = JSONObject().put("id", id).put("type", "result").put("success", success)

    @Test fun rejectsFailedStatusButLeavesOtherSubscriptionsAlone() {
        val status = BridgeStatusAcks()
        status.sent(3, 0)
        assertEquals(BridgeStatusAcks.Result.Ignored, status.acknowledge(result(1, false)))
        assertEquals(BridgeStatusAcks.Result.Ignored, status.acknowledge(result(2, false)))
        assertEquals(BridgeStatusAcks.Result.Rejected, status.acknowledge(result(3, false)))
        assertEquals(BridgeStatusAcks.Result.Ignored, status.acknowledge(result(3, false)))
    }

    @Test fun acknowledgedStatusCannotExpireOrBeRejectedAgain() {
        val status = BridgeStatusAcks()
        status.sent(3, 0)
        assertEquals(BridgeStatusAcks.Result.Accepted, status.acknowledge(result(3, true)))
        assertFalse(status.expired(20_000_000_000L))
        assertEquals(BridgeStatusAcks.Result.Ignored, status.acknowledge(result(3, false)))
    }

    @Test fun oldestOutstandingStatusHasAFiniteDeadline() {
        val status = BridgeStatusAcks()
        assertNull(status.remainingMillis(0))
        status.sent(3, 100)
        status.sent(4, 1_000_000_100L)
        assertEquals(10_000L, status.remainingMillis(100))
        assertEquals(1L, status.remainingMillis(10_000_000_099L))
        assertFalse(status.expired(10_000_000_099L))
        assertTrue(status.expired(10_000_000_100L))
        assertEquals(0L, status.remainingMillis(10_000_000_100L))
        status.acknowledge(result(3, true))
        assertFalse(status.expired(10_000_000_100L))
        assertTrue(status.expired(11_000_000_100L))
    }

    @Test fun trackingStaysBoundedWhenServerNeverAcknowledges() {
        val status = BridgeStatusAcks()
        repeat(32) { assertTrue(status.sent(it + 2, 0)) }
        assertFalse(status.sent(34, 0))
        status.acknowledge(result(2, true))
        assertTrue(status.sent(34, 1))
    }

    @Test fun malformedOrEventIdsCannotAcknowledgeStatus() {
        val status = BridgeStatusAcks()
        status.sent(3, 0)
        assertEquals(BridgeStatusAcks.Result.Ignored, status.acknowledge(result(3, true).put("id", "3")))
        assertEquals(BridgeStatusAcks.Result.Ignored, status.acknowledge(result(3, true).put("id", 3.5)))
        assertEquals(BridgeStatusAcks.Result.Ignored, status.acknowledge(result(3, true).put("type", "event")))
        assertTrue(status.expired(10_000_000_000L))
    }
}
