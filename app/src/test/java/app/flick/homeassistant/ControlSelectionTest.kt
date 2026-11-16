package app.flick.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlSelectionTest {
    @Test fun missingDiscoveredEntitiesDoNotEraseExistingSelections() {
        val existing = listOf(HaControlSpec("light.floor", "Floor lamp"), HaControlSpec("switch.offline", "Unavailable device"))
        val discovered = listOf(HaEntity("light.floor", "Floor lamp", "light"))
        assertEquals(existing, selectedControlSpecs(discovered, existing.map { it.entityId }.toSet(), existing))
    }

    @Test fun anEmptyDiscoveryStillPreservesExistingSelection() {
        val existing = listOf(HaControlSpec("light.floor", "Floor lamp"))
        assertEquals(existing, selectedControlSpecs(emptyList(), setOf("light.floor"), existing))
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
