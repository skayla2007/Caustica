package dev.comfyfluffy.caustica.rt.terrain;

/** Bounds concurrent build allocations without excluding any selected geometry. */
final class RtVoxyBuildBudget {
    static final long MIN = 32L << 20, MAX = 256L << 20;

    static long limit(long usage, long budget) {
        if (budget <= 0) return MAX;
        return Math.max(MIN, Math.min(MAX, budget - usage - (128L << 20)));
    }

    static boolean canStart(long inFlight, long estimated, long limit) {
        // An oversized mesh still makes progress after earlier builds complete.
        return inFlight == 0 || estimated <= Math.max(0, limit - inFlight);
    }

    static long estimate(RtTerrainMesher.PackedSection packed) {
        long upload = ((long)packed.positions().length + packed.indices().length
                + packed.uvs().length + packed.material().length) * 4;
        return upload * 2 + (long)packed.indices().length / 3 * 64;
    }
}
