package app.flick.integration

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class BridgeHistoryRestoreTest {
    @Test fun `missing history and a saved empty history allow the first request`() {
        assertTrue(BridgeRequestHistory(readBridgeRequestHistory(null)).claim("first"))
        assertTrue(BridgeRequestHistory(readBridgeRequestHistory("[]")).claim("first"))
    }

    @Test fun `restoring valid history keeps completed requests claimed`() {
        val restored = BridgeRequestHistory(readBridgeRequestHistory("[\"first\",\"second\"]"))
        assertFalse(restored.claim("first"))
        assertFalse(restored.claim("second"))
        assertTrue(restored.claim("third"))
    }

    @Test fun `malformed history is rejected instead of restored as empty`() {
        listOf("", "[\"first\"", "['first']", "[\"first\",]", "[] trailing", "[],\"extra\":1").forEach { raw ->
            assertThrows(Exception::class.java) { readBridgeRequestHistory(raw) }
        }
    }

    @Test fun `wrong JSON types cannot erase existing history`() {
        listOf("null", "{}", "[1]", "[true]", "[null]", "[{}]").forEach { raw ->
            assertThrows(Exception::class.java) { readBridgeRequestHistory(raw) }
        }
    }

    @Test fun `invalid and duplicate request IDs are rejected without filtering`() {
        listOf("[\"\"]", "[\"invalid id\"]", "[\"${"a".repeat(65)}\"]", "[\"first\",\"first\"]",
            "[\"first\",\"\\u0066irst\"]").forEach { raw ->
            assertThrows(Exception::class.java) { readBridgeRequestHistory(raw) }
        }
    }

    @Test fun `the complete saved request window is accepted and a larger window is rejected`() {
        val ids = (0 until 256).map { "request-$it" }
        assertEquals(ids, readBridgeRequestHistory(JSONArray(ids).toString()))
        assertThrows(Exception::class.java) { readBridgeRequestHistory(JSONArray(ids + "overflow").toString()) }
        assertThrows(Exception::class.java) { readBridgeRequestHistory(" ".repeat(24 * 1024 + 1)) }
    }
}
