package dev.comfyfluffy.caustica.rt.accel;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtTlasLayoutTest {
    private static RtAccel.Instance instance(long address, int id, float x) {
        return new RtAccel.Instance(new float[]{1,0,0,x,0,1,0,0,0,0,1,0}, address, id);
    }

    @Test void reorderAndCountChangesPreserveSpatialSlotsAndCustomIds() {
        var a = instance(10,0,10); var b = instance(20,1,20); var c = instance(30,2,30);
        var initial = RtTlasLayout.plan(List.of(a,b,c), List.of(), 5);
        var nextA = instance(10,1,9); var nextC = instance(30,0,29); var d = instance(40,2,40);
        var next = RtTlasLayout.plan(List.of(nextC,nextA,d), initial.instances(), 5);
        assertSame(nextA, next.instances().get(0));
        assertSame(nextC, next.instances().get(2));
        assertSame(d, next.instances().get(1));
        assertEquals(1, next.added());
        assertEquals(3, next.instances().stream().filter(i -> i.mask() != 0).count());
        assertTrue(next.instances().stream().allMatch(i -> i.blasDeviceAddress() != 0));
        assertFalse(next.instances().stream().anyMatch(i -> i.blasDeviceAddress() == 20));
        var smaller = RtTlasLayout.plan(List.of(nextA), next.instances(), 5);
        assertEquals(1, smaller.instances().stream().filter(i -> i.mask() != 0).count());
        assertEquals(5, smaller.instances().size());
    }

    @Test void duplicateBlasInstancesAreNotLostAndRebuildBoundsQualityDrift() {
        var shared = instance(10,0,10); var other = instance(10,1,20);
        var plan = RtTlasLayout.plan(List.of(shared,other), List.of(), 4);
        var reordered = RtTlasLayout.plan(List.of(other,shared), plan.instances(), 4);
        assertTrue(reordered.instances().contains(shared));
        assertTrue(reordered.instances().contains(other));
        assertTrue(RtTlasLayout.shouldRefit(true,1000,1000,10,31));
        assertFalse(RtTlasLayout.shouldRefit(true,1000,1000,126,0));
        assertFalse(RtTlasLayout.shouldRefit(true,1000,1000,10,32));
        assertFalse(RtTlasLayout.shouldRefit(false,1000,1000,10,0));
        assertFalse(RtTlasLayout.shouldRefit(true,999,1000,0,0));
    }
}
