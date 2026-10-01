package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtVoxySelectionGateTest {
    @Test void stableFrameReusesSelectionButSceneAndClippingChangesInvalidateIt() {
        var gate = new RtVoxySelectionGate();
        Object scene = new Object();
        var coverage = new LongOpenHashSet(new long[]{12, 34});
        assertTrue(gate.refresh(scene, coverage));
        gate.remember(scene, coverage);
        assertFalse(gate.refresh(scene, new LongOpenHashSet(new long[]{34, 12})));
        assertTrue(gate.refresh(new Object(), coverage));
        assertTrue(gate.refresh(scene, new LongOpenHashSet(new long[]{12})));
        gate.clear();
        assertTrue(gate.refresh(scene, coverage));
    }

    @Test void changedPlanDoesNotBecomeCurrentUntilAcknowledged() {
        var gate = new RtVoxySelectionGate();
        Object scene = new Object();
        var coverage = new LongOpenHashSet();
        gate.remember(scene, coverage);
        Object moved = new Object();
        assertTrue(gate.refresh(moved, coverage));
        assertTrue(gate.refresh(moved, coverage));
        gate.remember(moved, coverage);
        assertFalse(gate.refresh(moved, coverage));
    }
}
