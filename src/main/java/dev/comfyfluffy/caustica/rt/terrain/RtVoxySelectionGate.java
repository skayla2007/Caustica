package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.longs.LongSet;

/** Reuses the request plan while provider geometry and real-section clipping stay unchanged. */
final class RtVoxySelectionGate {
    private Object scene;
    private LongSet coverage;

    boolean refresh(Object nextScene, LongSet nextCoverage) {
        return scene != nextScene || coverage == null || !coverage.equals(nextCoverage);
    }

    void remember(Object nextScene, LongSet nextCoverage) {
        scene = nextScene;
        coverage = nextCoverage;
    }

    void clear() { scene = null; coverage = null; }
}
