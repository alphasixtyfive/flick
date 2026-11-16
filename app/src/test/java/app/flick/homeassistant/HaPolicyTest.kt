package app.flick.homeassistant

import org.junit.Assert.*
import org.junit.Test

class HaPolicyTest {
    @Test fun onlyExplicitSupportedEntityDomainsCanBeSelected() {
        for (entity in listOf("light.example", "switch.example", "input_boolean.example", "scene.example", "script.example", "sensor.example", "binary_sensor.example")) {
            assertNotNull(HaPolicy.domain(entity))
        }
        for (entity in listOf("lock.front_door", "cover.gate", "alarm_control_panel.house", "light.example/../secret", "light.example?token=secret", "light.example\n")) {
            assertNull(HaPolicy.domain(entity))
        }
    }

    @Test fun canonicalAddressRejectsEmbeddedCredentialsAndOtherProtocols() {
        assertEquals("http://example.test:8123", HaPolicy.canonicalUrl(" http://example.test:8123/ "))
        assertEquals("https://example.test/ha", HaPolicy.canonicalUrl("https://example.test/ha/"))
        for (url in listOf("https://user:password@example.test", "https://example.test/?token=x", "https://example.test/#secret", "file:///private", "http://example.test:0", "http://example.test:99999")) {
            assertTrue(runCatching { HaPolicy.canonicalUrl(url) }.isFailure)
        }
    }

    @Test fun controlConfigurationIsBoundedAndLabelsArePlainText() {
        assertEquals(listOf(HaControlSpec("light.example", "Example")), HaPolicy.controls(listOf(
            HaControlSpec("light.example", "Ex\nample"), HaControlSpec("light.example", "Duplicate")
        )))
        assertTrue(runCatching { HaPolicy.controls((0..16).map { HaControlSpec("light.example_$it", "Light") }) }.isFailure)
        assertTrue(runCatching { HaPolicy.controls(listOf(HaControlSpec("lock.example", "Door"))) }.isFailure)
    }

    @Test fun unknownAndUnavailableCanNeverConfirmPower() {
        assertTrue(HaPolicy.confirms("on", true))
        assertTrue(HaPolicy.confirms("off", false))
        for (state in listOf(null, "unknown", "unavailable", "pending", "connected")) {
            assertFalse(HaPolicy.confirms(state, true))
            assertFalse(HaPolicy.confirms(state, false))
        }
    }
}
