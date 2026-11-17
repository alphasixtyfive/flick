package app.flick.hometext

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeTextTest {
    private fun event(text: String = "Ready", generation: Long = 1, revision: Long = 1) = JSONObject()
        .put("profile_id", "adult").put("profile_generation", generation).put("revision", revision)
        .put("text", text).put("show_for_children", false)

    private fun rejected(value: JSONObject) {
        try { HomeTextProtocol.read(value); fail("Invalid Home screen text accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun boundsTheMessageAndCountsEmojiAsCodepoints() {
        val emoji = String(Character.toChars(0x1F4A1))
        val text = emoji.repeat(120)
        assertEquals(text, HomeTextProtocol.read(event(text)).text)
        rejected(event(emoji.repeat(121)))
        rejected(event("x".repeat(121)))
        assertEquals("", HomeTextProtocol.read(event("")).text)
    }

    @Test fun rejectsMultilineControlsBidiOverridesAndMalformedSurrogates() {
        listOf("a\nb", "bad\ttext", "bad\rtext", "bad\u0000text", "\u0085", "\u2028", "\u2029",
            "\u061C", "\u200E", "\u200F", "\u202A", "\u202E", "\u2066", "\u206F", "\uD800", "\uDC00").forEach {
            rejected(event(it))
        }
        val joinedEmoji = "👩\u200D💻\uFE0F\u200C"
        assertEquals(joinedEmoji, HomeTextProtocol.read(event(joinedEmoji)).text)
    }

    @Test fun acceptsOnlyTheFiveCurrentWireFields() {
        rejected(event().put("position", "top_left"))
        rejected(event().put("unknown", true))
        rejected(event().apply { remove("text") })
        rejected(event().apply { remove("show_for_children") })
    }

    @Test fun keepsFieldTypesAndAuthorityNumbersStrict() {
        rejected(event().put("profile_id", 2))
        rejected(event().put("profile_id", "../adult"))
        rejected(event().put("profile_id", "x".repeat(65)))
        rejected(event().put("profile_generation", "1"))
        rejected(event().put("profile_generation", 0))
        rejected(event().put("revision", 1.5))
        rejected(event().put("revision", 9_007_199_254_740_992L))
        rejected(event().put("show_for_children", "false"))
        rejected(event().put("text", JSONObject()))
        rejected(event().put("text", JSONObject.NULL))
    }

    @Test fun markupAndTemplatesRemainLiteralText() {
        val text = "<b>{{ states('sensor.example') }}</b>"
        assertEquals(text, HomeTextProtocol.read(event(text)).text)
    }

    @Test fun childVisibilityRequiresExplicitPermissionAndMatchingProfileSession() {
        val profile = HomeTextProfile("child", true, 7)
        val snapshot = HomeTextProtocol.read(event().put("profile_id", "child"))
        val state = HomeTextState(HomeTextContext(profile, 1), HomeTextStatus.Connected, snapshot)
        assertNull(state.visibleFor("child", true, 7))
        val allowed = state.copy(snapshot = snapshot.copy(showForChildren = true))
        assertNotNull(allowed.visibleFor("child", true, 7))
        assertNull(allowed.visibleFor("child", true, 8))
        assertNull(allowed.visibleFor("adult", false, 7))
        assertNull(allowed.visibleFor("child", false, 7))
        assertNull(allowed.copy(context = null).visibleFor("child", true, 7))
    }

    @Test fun onlyConnectedCurrentNonblankContentIsVisible() {
        val profile = HomeTextProfile("adult", false, 2)
        val snapshot = HomeTextProtocol.read(event())
        val state = HomeTextState(HomeTextContext(profile, 1), HomeTextStatus.Connected, snapshot)
        assertNotNull(state.visibleFor("adult", false, 2))
        assertNotNull(state.copy(snapshot = snapshot.copy(text = "Current message")).visibleFor("adult", false, 2))
        assertNull(state.copy(snapshot = snapshot.copy(text = " ")).visibleFor("adult", false, 2))
        HomeTextStatus.entries.filter { it != HomeTextStatus.Connected }.forEach {
            assertNull(state.copy(status = it).visibleFor("adult", false, 2))
        }
        assertNull(state.copy(context = HomeTextContext(profile, 3)).visibleFor("adult", false, 2))
    }

    @Test fun diagnosticStringsRedactTheMessage() {
        val snapshot = HomeTextProtocol.read(event("Private Home screen message"))
        listOf(snapshot.toString(), HomeTextState(snapshot = snapshot).toString()).forEach {
            assertFalse(it.contains(snapshot.text))
        }
    }
}
