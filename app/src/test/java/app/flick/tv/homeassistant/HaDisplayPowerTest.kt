package app.flick.tv.homeassistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HaDisplayPowerTest {
    private fun player(id: String, deviceClass: Any? = "tv") = JSONObject().put("entity_id", id)
        .put("attributes", JSONObject().put("friendly_name", "Living room TV").apply {
            if (deviceClass != null) put("device_class", deviceClass)
        })

    @Test fun standbyDiscoveryUsesTvMetadataRatherThanNames() {
        assertTrue(HaDisplayPowerPolicy.isTelevision(player("media_player.screen")))
        assertFalse(HaDisplayPowerPolicy.isTelevision(player("media_player.lounge_tv", "speaker")))
        assertFalse(HaDisplayPowerPolicy.isTelevision(player("media_player.television", "receiver")))
        assertTrue(HaDisplayPowerPolicy.isTelevision(player("media_player.browser").put("attributes",
            JSONObject().put("friendly_name", "Desktop").put("device_class", "tv"))))
    }

    @Test fun standbyDiscoveryRejectsMissingOrMalformedClassification() {
        assertFalse(HaDisplayPowerPolicy.isTelevision(player("media_player.screen", null)))
        assertFalse(HaDisplayPowerPolicy.isTelevision(player("media_player.screen", 1)))
        assertFalse(HaDisplayPowerPolicy.isTelevision(player("media_player.screen", "TV")))
        assertFalse(HaDisplayPowerPolicy.isTelevision(player("light.screen")))
        assertFalse(HaDisplayPowerPolicy.isTelevision(player("media_player.screen").put("attributes", "tv")))
    }

    @Test fun savedSourceRemainsValidWithoutDiscoveryMetadata() {
        val saved = HaControlSpec("media_player.old_screen", "Saved TV")
        assertFalse(HaDisplayPowerPolicy.isTelevision(player(saved.entityId, null)))
        assertEquals(saved, HaDisplayPowerPolicy.selection(saved))
    }

    @Test fun onlyMediaPlayerIdentifiersCanBeObservedAndTheyAreNeverControls() {
        assertNotNull(HaDisplayPowerPolicy.selection(HaControlSpec("media_player.screen", "Screen")))
        assertNull(HaPolicy.domain("media_player.screen"))
        listOf("light.screen", "media_player.screen/other", "media_player.screen?token=x", "media_player.Screen").forEach {
            assertNull(HaDisplayPowerPolicy.entityId(it))
        }
    }

    @Test fun monitoringWorksWithoutAddingAnyActionableControl() {
        val configuration = HaConfiguration(displayPowerEntity = HaControlSpec("media_player.screen", "Screen"))
        assertTrue(configuration.controls.isEmpty())
        assertEquals(setOf("media_player.screen"), configuration.observedEntityIds)
        assertTrue(HaConfiguration().observedEntityIds.isEmpty())
        assertEquals(setOf("media_player.screen", "light.lamp"), configuration.copy(
            controls = listOf(HaControlSpec("light.lamp", "Lamp"))).observedEntityIds)
    }

    @Test fun onlyExplicitActiveStatesImplyTheTvIsOn() {
        listOf("on", "idle", "playing", "paused", "buffering").forEach { assertEquals(HaDisplayPower.On, HaDisplayPowerPolicy.state(it)) }
        assertEquals(HaDisplayPower.Off, HaDisplayPowerPolicy.state("off"))
        listOf(null, "unknown", "unavailable", "", "anything_new").forEach { assertEquals(HaDisplayPower.Unknown, HaDisplayPowerPolicy.state(it)) }
    }

    @Test fun unconfiguredMonitorHasNoContinuityRequirements() {
        val monitor = HaDisplayPowerMonitor()
        monitor.connection(false)
        monitor.connection(true)
        monitor.observe("media_player.screen", "off")
        assertEquals(HaDisplayPower.Disabled, monitor.state)
        assertEquals(0L, monitor.interruptions)
        assertNull(monitor.snapshotRevision())
    }

    @Test fun oldSnapshotCannotOverwriteAnEventOrRestoreContinuityAfterDisconnect() {
        val monitor = connected()
        val initial = monitor.snapshotRevision()!!
        monitor.observe("media_player.screen", "off")
        monitor.observe("media_player.screen", "on", initial)
        assertEquals(HaDisplayPower.Off, monitor.state)
        val beforeDisconnect = monitor.snapshotRevision()!!
        monitor.connection(false)
        monitor.observe("media_player.screen", "on", beforeDisconnect)
        assertEquals(HaDisplayPower.Unknown, monitor.state)
    }

    @Test fun rapidPowerCyclesRemainVisibleAfterStateFlowConflation() {
        val monitor = connected()
        monitor.observe("media_player.screen", "on")
        val before = monitor.interruptions
        monitor.observe("media_player.screen", "off")
        monitor.observe("media_player.screen", "on")
        assertEquals(HaDisplayPower.On, monitor.state)
        assertEquals(before + 1, monitor.interruptions)
    }

    @Test fun anOutageRevokesOnceAndReconnectNeverRestoresAuthorization() {
        val monitor = connected()
        monitor.observe("media_player.screen", "on")
        val before = monitor.interruptions
        monitor.connection(false)
        monitor.connection(false)
        monitor.connection(true)
        assertEquals(HaDisplayPower.Unknown, monitor.state)
        assertEquals(before + 1, monitor.interruptions)
        monitor.observe("media_player.screen", "on")
        assertEquals(before + 1, monitor.interruptions)
    }

    @Test fun unrelatedEventsAndOldSelectionsCannotChangeTheChosenTv() {
        val monitor = connected()
        monitor.observe("media_player.screen", "on")
        monitor.observe("media_player.receiver", "off")
        assertEquals(HaDisplayPower.On, monitor.state)
        val old = monitor.snapshotRevision()!!
        monitor.select("media_player.other_screen")
        monitor.connection(true)
        monitor.observe("media_player.screen", "on", old)
        assertEquals(HaDisplayPower.Unknown, monitor.state)
        monitor.select(null)
        assertEquals(HaDisplayPower.Disabled, monitor.state)
    }

    private fun connected() = HaDisplayPowerMonitor().apply {
        select("media_player.screen")
        connection(true)
    }
}
