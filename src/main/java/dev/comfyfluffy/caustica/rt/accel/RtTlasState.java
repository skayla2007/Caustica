package dev.comfyfluffy.caustica.rt.accel;

import java.util.Arrays;
import java.util.List;

/** Exact instance state plus the generation of mutable BLAS contents. */
final class RtTlasState {
    private List<RtAccel.Instance> base;
    private List<RtAccel.Instance> dynamic = List.of();
    private long geometryRevision;

    boolean matches(List<RtAccel.Instance> base, List<RtAccel.Instance> dynamic, long geometryRevision) {
        if (this.base != base || this.dynamic.size() != dynamic.size()) return false;
        if (!dynamic.isEmpty() && this.geometryRevision != geometryRevision) return false;
        for (int i = 0; i < dynamic.size(); i++) {
            var a = this.dynamic.get(i);
            var b = dynamic.get(i);
            if (a.blasDeviceAddress() != b.blasDeviceAddress() || a.customIndex() != b.customIndex()
                    || a.mask() != b.mask() || a.sbtRecordOffset() != b.sbtRecordOffset()
                    || !Arrays.equals(a.transform3x4(), b.transform3x4())) return false;
        }
        return true;
    }

    void remember(List<RtAccel.Instance> base, List<RtAccel.Instance> dynamic, long geometryRevision) {
        this.base = base;
        this.dynamic = dynamic.stream().map(i -> new RtAccel.Instance(i.transform3x4().clone(),
                i.blasDeviceAddress(), i.customIndex(), i.mask(), i.sbtRecordOffset())).toList();
        this.geometryRevision = geometryRevision;
    }
}
