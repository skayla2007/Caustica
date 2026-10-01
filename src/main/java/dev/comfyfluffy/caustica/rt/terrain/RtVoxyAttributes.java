package dev.comfyfluffy.caustica.rt.terrain;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/** Lossless quad material palette; UVs follow the provider's object-space voxel axes. */
final class RtVoxyAttributes {
    private RtVoxyAttributes() {}

    record Palette(float[] indices, float[] records) {}

    static Palette pack(float[] records) {
        Palette palette = palette(records);
        return palette.indices().length + palette.records().length < records.length
                ? palette : new Palette(records, new float[1]);
    }

    static Palette palette(float[] records) {
        if (records.length % 3 != 0) throw new IllegalArgumentException("Voxy records contain three words");
        var byModel = new Int2ObjectOpenHashMap<Long2IntOpenHashMap>();
        var palette = new FloatArrayList();
        float[] indices = new float[records.length / 3];
        for (int quad = 0; quad < indices.length; quad++) {
            int offset = quad * 3;
            int model = Float.floatToRawIntBits(records[offset + 2]);
            var lookup = byModel.get(model);
            if (lookup == null) {
                lookup = new Long2IntOpenHashMap(); lookup.defaultReturnValue(-1);
                byModel.put(model, lookup);
            }
            long key = ((long)Float.floatToRawIntBits(records[offset]) << 32)
                    | Integer.toUnsignedLong(Float.floatToRawIntBits(records[offset + 1]));
            int index = lookup.get(key);
            if (index == -1) {
                index = palette.size() / 3;
                lookup.put(key, index);
                palette.add(records[offset]); palette.add(records[offset + 1]); palette.add(records[offset + 2]);
            }
            indices[quad] = Float.intBitsToFloat(index);
        }
        return new Palette(indices, palette.toFloatArray());
    }

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
