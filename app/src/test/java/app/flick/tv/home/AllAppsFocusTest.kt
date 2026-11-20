package app.flick.tv.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AllAppsFocusTest {
    @Test fun reorderedAppsRetainTheirFocusedPackage() {
        assertEquals("b", drawerFocusTarget(listOf("b", "c", "a"), "b", 1))
    }

    @Test fun removingTheFocusedAppChoosesItsNextNeighbour() {
        assertEquals("c", drawerFocusTarget(listOf("a", "c", "d"), "b", 1))
    }

    @Test fun removingTheLastAppChoosesThePreviousNeighbour() {
        assertEquals("b", drawerFocusTarget(listOf("a", "b"), "c", 2))
    }

    @Test fun emptyCatalogueHasNoFocusTarget() {
        assertNull(drawerFocusTarget(emptyList(), "a", 0))
    }

    @Test fun firstOpeningStartsAtTheFirstApp() {
        assertEquals("a", drawerFocusTarget(listOf("a", "b"), null, 0))
    }

    @Test fun aSingleAppAndAnInvalidIndexRemainSafe() {
        assertEquals("a", drawerFocusTarget(listOf("a"), "removed", 20))
        assertEquals("a", drawerFocusTarget(listOf("a", "b"), null, -1))
    }
}
