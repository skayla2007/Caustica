package dev.comfyfluffy.caustica.rt.accel;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Arrays;
import java.util.List;

/** Stable spatial slots with masked live references keep terrain TLAS primitive counts updateable. */
final class RtTlasLayout {
    record Plan(List<RtAccel.Instance> instances, int added) {}

    static Plan plan(List<RtAccel.Instance> desired, List<RtAccel.Instance> previous, int capacity) {
        if (desired.isEmpty() || desired.size() > capacity) throw new IllegalArgumentException("Invalid padded TLAS size");
        var slots = new RtAccel.Instance[capacity];
        var oldPositions = new Long2IntOpenHashMap(previous.size());
        oldPositions.defaultReturnValue(-1);
        for (int i = 0; i < previous.size(); i++) {
            var instance = previous.get(i);
            if (instance.mask() != 0) oldPositions.put(instance.blasDeviceAddress(), i);
        }
        var unmatched = new java.util.ArrayList<RtAccel.Instance>();
        for (var instance : desired) {
            if (instance.blasDeviceAddress() == 0) throw new IllegalArgumentException("Padded TLAS needs a live BLAS reference");
            int index = oldPositions.get(instance.blasDeviceAddress());
            if (index >= 0 && index < capacity && slots[index] == null) slots[index] = instance;
            else unmatched.add(instance);
        }
        int cursor = 0;
        for (var instance : unmatched) {
            while (slots[cursor] != null) cursor++;
            slots[cursor] = instance;
        }
        var anchor = desired.getFirst();
        var masked = new RtAccel.Instance(anchor.transform3x4(), anchor.blasDeviceAddress(), anchor.customIndex(),
                0, anchor.sbtRecordOffset());
        // Reference zero denotes an inactive instance and cannot change to live during Vulkan UPDATE.
        for (int i = 0; i < capacity; i++) if (slots[i] == null) slots[i] = masked;
        return new Plan(Arrays.asList(slots), unmatched.size());
    }

    static boolean shouldRefit(boolean paddedBefore, int previousCount, int capacity, int added, int updates) {
        return paddedBefore && previousCount == capacity && updates < 32 && added <= Math.max(32, capacity / 8);
    }
}
