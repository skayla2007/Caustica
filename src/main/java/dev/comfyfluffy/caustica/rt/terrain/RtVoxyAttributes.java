package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

/** One 12-byte material record per Voxy quad; UVs follow the provider's object-space voxel axes. */
final class RtVoxyAttributes {
    private RtVoxyAttributes() {}

    static void append(FloatArrayList materials, int material, int tint, int model, int face,
                       int tintMode, int texture, int level, float emission) {
        if (material < 0 || material > 65535 || model < 0 || model > 65535 || face < 0 || face > 5 ||
                tintMode < 0 || tintMode > 3 || texture < 0 || texture > 511 || level < 0 || level > 4)
            throw new IllegalArgumentException("Voxy material exceeds the compact attribute range");
        int light = Math.round(emission*15);
        if (light < 0 || light > 15) throw new IllegalArgumentException("Voxy emission is a four-bit block light level");
        materials.add(Float.intBitsToFloat(material | (texture << 16) | (level << 25) | (light << 28)));
        materials.add(Float.intBitsToFloat((tint & 0xffffff) | (face << 24) | (tintMode << 27)));
        materials.add(Float.intBitsToFloat(model));
    }
}
