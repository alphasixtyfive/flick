package app.flick.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlSelectionTest {
    @Test fun individualEditsPreserveOrderAndUseTheLatestControls() {
        val lamp = HaControlSpec("light.lamp", "Lamp")
        val camera = HaControlSpec("camera.entry", "Front door")
        val sensor = HaControlSpec("sensor.temp", "Temperature")
        val afterCamera = changeControlSelection(listOf(lamp), camera, enabled = true)
        val afterSensor = changeControlSelection(afterCamera, sensor, enabled = true)
        assertEquals(listOf(lamp, camera, sensor), afterSensor)
        assertEquals(afterSensor, changeControlSelection(afterSensor, camera.copy(name = "Other name"), enabled = true))
        assertEquals(listOf(lamp, sensor), changeControlSelection(afterSensor, camera, enabled = false))
    }

    @Test fun removingAControlMakesRoomForTheNextEdit() {
        val controls = (1..HaPolicy.maxControls).map { HaControlSpec("light.lamp_$it", "Lamp $it") }
        val camera = HaControlSpec("camera.entry", "Front door")
        org.junit.Assert.assertThrows(HaException::class.java) { changeControlSelection(controls, camera, enabled = true) }
        val afterRemove = changeControlSelection(controls, controls.first(), enabled = false)
        assertEquals(controls.drop(1) + camera, changeControlSelection(afterRemove, camera, enabled = true))
    }
    @Test fun populatedGroupsAppearInMenuOrderAndSortTogetherAcrossDomains() {
        val groups = groupControlEntities(listOf(
            HaEntity("sensor.meter", "Meter", "sensor"),
            HaEntity("switch.zebra", "Zebra", "switch"),
            HaEntity("camera.entry", "Entry", "camera"),
            HaEntity("light.floor", "Floor", "light"),
            HaEntity("input_boolean.alarm", "alarm", "input_boolean"),
            HaEntity("binary_sensor.door", "Door", "binary_sensor"),
            HaEntity("cover.gate", "Gate", "cover"),
        ))
        assertEquals(listOf(HaEntityGroup.Cameras, HaEntityGroup.Lights, HaEntityGroup.Switches, HaEntityGroup.Sensors),
            groups.keys.toList())
        assertEquals(listOf("alarm", "Zebra"), groups.getValue(HaEntityGroup.Switches).map { it.name })
        assertEquals(listOf("Door", "Meter"), groups.getValue(HaEntityGroup.Sensors).map { it.name })
    }

    @Test fun selectedNamesSortCaseInsensitivelyWithStableTies() {
        val rows = listOf(HaControlSpec("light.z", "zebra"), HaControlSpec("light.b", "Alpha"), HaControlSpec("light.a", "alpha"))
        assertEquals(listOf("light.a", "light.b", "light.z"), rows.alphabetical().map { it.entityId })
    }

    @Test fun matchingFriendlyNamesNeedEntityIdsEvenAcrossDomains() {
        val rows = listOf(HaControlSpec("switch.lounge", "Lounge"), HaControlSpec("input_boolean.lounge", "lounge"),
            HaControlSpec("switch.guest", "Guest"))
        assertEquals(setOf("switch.lounge", "input_boolean.lounge"), ambiguousControlIds(rows))
    }

    @Test fun missingDiscoveredEntitiesDoNotEraseExistingSelections() {
        val existing = listOf(HaControlSpec("light.floor", "Floor lamp"), HaControlSpec("switch.offline", "Unavailable device"))
        val discovered = listOf(HaEntity("light.floor", "Floor lamp", "light"))
        assertEquals(existing, selectedControlSpecs(discovered, existing.map { it.entityId }.toSet(), existing))
    }

    @Test fun anEmptyDiscoveryStillPreservesExistingSelection() {
        val existing = listOf(HaControlSpec("light.floor", "Floor lamp"))
        assertEquals(existing, selectedControlSpecs(emptyList(), setOf("light.floor"), existing))
    }

    @Test fun addingACameraKeepsTheSavedOrderAndNames() {
        val existing = listOf(HaControlSpec("switch.fan", "Fan"), HaControlSpec("light.floor", "Floor lamp"))
        val discovered = listOf(HaEntity("camera.entry", "Front door", "camera"), HaEntity("light.floor", "Lamp", "light"))
        val selected = setOf("camera.entry", "light.floor", "switch.fan")
        assertEquals(existing + HaControlSpec("camera.entry", "Front door"), selectedControlSpecs(discovered, selected, existing))
    }

    @Test fun explicitDeselectionRemovesAnUnseenDevice() {
        val existing = listOf(HaControlSpec("switch.offline", "Unavailable device"))
        assertEquals(emptyList<HaControlSpec>(), selectedControlSpecs(emptyList(), emptySet(), existing))
    }

    @Test fun unseenSelectionsCountTowardsTheLimit() {
        val selected = (1..HaPolicy.maxControls).map { "light.unseen_$it" }.toSet()
        assertNull(toggleControlSelection(selected, "light.new"))
        assertEquals(selected - "light.unseen_1", toggleControlSelection(selected, "light.unseen_1"))
    }

    @Test fun anOverLimitImportedSelectionCanBeReduced() {
        val selected = (1..HaPolicy.maxControls + 1).map { "light.imported_$it" }.toSet()
        val reduced = requireNotNull(toggleControlSelection(selected, "light.imported_1"))
        assertEquals(HaPolicy.maxControls, reduced.size)
        assertNull(toggleControlSelection(reduced, "light.new"))
        val withSpace = requireNotNull(toggleControlSelection(reduced, "light.imported_2"))
        assertEquals(withSpace + "light.new", toggleControlSelection(withSpace, "light.new"))
    }

    @Test fun duplicateSavedIdsDoNotInflateOrDuplicateSelections() {
        val spec = HaControlSpec("light.floor", "Floor lamp")
        assertEquals(listOf(spec), selectedControlSpecs(emptyList(), setOf(spec.entityId), listOf(spec, spec)))
    }
}
