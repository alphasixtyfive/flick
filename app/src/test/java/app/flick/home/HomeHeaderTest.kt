package app.flick.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeaderTest {
    @Test fun largerControlGroupLimitsBothSidesEqually() {
        assertEquals(440, homeMessageWidth(864, 200, 140, 12))
    }

    @Test fun largerClockLimitsBothSidesEqually() {
        assertEquals(440, homeMessageWidth(864, 140, 200, 12))
    }

    @Test fun narrowHeaderHasNoMessageSpace() {
        assertEquals(0, homeMessageWidth(420, 220, 140, 12))
    }

    @Test fun exactFitDoesNotOverlapEitherGroup() {
        assertEquals(0, homeMessageWidth(424, 200, 140, 12))
    }

    @Test fun doublingThePixelViewportPreservesTheLayout() {
        assertEquals(2 * homeMessageWidth(864, 180, 128, 12),
            homeMessageWidth(1728, 360, 256, 24))
    }
}
