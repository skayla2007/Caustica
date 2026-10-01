package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtVoxyBuildBudgetTest {
    @Test void overloadStillAllowsEveryOversizedMeshToAdvanceAfterEarlierBuildsComplete() {
        long limit = RtVoxyBuildBudget.limit(8L << 30, 6L << 30);
        assertEquals(32L << 20, limit);
        assertFalse(RtVoxyBuildBudget.canStart(1, 500L << 20, limit));
        assertTrue(RtVoxyBuildBudget.canStart(0, 500L << 20, limit));
        assertTrue(RtVoxyBuildBudget.canStart(16L << 20, 16L << 20, limit));
        assertFalse(RtVoxyBuildBudget.canStart(16L << 20, 17L << 20, limit));
    }

    @Test void unknownBudgetDoesNotArtificiallyThrottleAndAmpleHeadroomIsCapped() {
        assertEquals(256L << 20, RtVoxyBuildBudget.limit(0,0));
        assertEquals(256L << 20, RtVoxyBuildBudget.limit(1L << 30,8L << 30));
    }
}
