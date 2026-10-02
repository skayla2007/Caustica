package dev.comfyfluffy.caustica.rt.terrain;

/** Foreground-latency budget independent of the available allocation budget. */
final class RtVoxyLoadBudget {
    static final long MIN_TRIANGLES = 65_536, MAX_TRIANGLES = 262_144;
    private long triangles = MAX_TRIANGLES;

    long update(long frameInterval) {
        if (frameInterval > 40_000_000L) triangles = Math.max(MIN_TRIANGLES, triangles / 2);
        else if (frameInterval < 35_000_000L) triangles = Math.min(MAX_TRIANGLES, triangles + 8192);
        return triangles;
    }

    static boolean canStart(long inFlight, long batch, long next, long limit) {
        // A single oversized mesh cannot be starved, but cannot accumulate behind earlier builds.
        return inFlight == 0 && batch == 0 || next <= Math.max(0, limit - inFlight - batch);
    }
}
