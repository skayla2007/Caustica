package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtVoxyLoadBudgetTest {
    @Test void contentionBacksOffAndSmoothFramesRecoverWithinBounds() {
        var budget = new RtVoxyLoadBudget();
        for (int i=0; i<20; i++) budget.update(50_000_000);
        assertEquals(RtVoxyLoadBudget.MIN_TRIANGLES, budget.update(50_000_000));
        for (int i=0; i<100; i++) budget.update(16_000_000);
        assertEquals(RtVoxyLoadBudget.MAX_TRIANGLES, budget.update(16_000_000));
    }

    @Test void oversizedMeshProgressesAloneAndConcurrentBuildsRespectWorkBudget() {
        assertTrue(RtVoxyLoadBudget.canStart(0,0,1_000_000,100_000));
        assertFalse(RtVoxyLoadBudget.canStart(50_000,0,1_000_000,100_000));
        assertFalse(RtVoxyLoadBudget.canStart(0,1_000_000,1,100_000));
        assertTrue(RtVoxyLoadBudget.canStart(40_000,30_000,30_000,100_000));
        assertFalse(RtVoxyLoadBudget.canStart(40_000,30_000,30_001,100_000));
    }
}
