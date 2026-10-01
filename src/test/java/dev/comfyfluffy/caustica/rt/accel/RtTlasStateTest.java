package dev.comfyfluffy.caustica.rt.accel;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtTlasStateTest {
    private static RtAccel.Instance instance(float x, long address, int mask) {
        return new RtAccel.Instance(new float[]{1,0,0,x,0,1,0,0,0,0,1,0}, address, 3, mask, 4);
    }

    @Test void staticTerrainReusesAcrossDynamicContentRevisionsButNotTerrainGenerations() {
        var state = new RtTlasState();
        var base = new ArrayList<RtAccel.Instance>();
        base.add(instance(0, 123, 255));
        state.remember(base, List.of(), 1);
        assertTrue(state.matches(base, List.of(), 2));
        assertFalse(state.matches(new ArrayList<>(base), List.of(), 2));
        assertFalse(state.matches(base, List.of(instance(0, 456, 255)), 2));
    }

    @Test void mutableBlasAndEveryDynamicInstanceFieldInvalidateReuse() {
        var state = new RtTlasState();
        List<RtAccel.Instance> base = List.of();
        var original = instance(0, 456, 255);
        state.remember(base, List.of(original), 7);
        assertTrue(state.matches(base, List.of(instance(0,456,255)), 7));
        assertFalse(state.matches(base, List.of(original), 8));
        assertFalse(state.matches(base, List.of(instance(1,456,255)), 7));
        assertFalse(state.matches(base, List.of(instance(0,457,255)), 7));
        assertFalse(state.matches(base, List.of(instance(0,456,1)), 7));
        assertFalse(state.matches(base, List.of(new RtAccel.Instance(original.transform3x4(),456,2,255,4)), 7));
        assertFalse(state.matches(base, List.of(new RtAccel.Instance(original.transform3x4(),456,3,255,0)), 7));
        original.transform3x4()[3] = 3;
        assertFalse(state.matches(base, List.of(original), 7));
    }
}
