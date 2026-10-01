package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import me.cortex.voxy.api.*;
import net.minecraft.tags.FluidTags;

/** Converts provider-owned planar geometry to RT triangles; never generates or simplifies LOD voxels. */
final class RtVoxyMesh {
    private static final class Bucket {
        final FloatArrayList positions = new FloatArrayList(), materials = new FloatArrayList();
        final IntArrayList indices = new IntArrayList();
    }

    static RtTerrainMesher.PackedSection convert(LodScene scene, LodMesh mesh, int textureSlot,
                                                 it.unimi.dsi.fastutil.longs.LongSet realSections,
                                                 RtMaterialRegistry.Snapshot snapshot) {
        boolean clipRealSections = !realSections.isEmpty();
        Bucket[] buckets = {new Bucket(), new Bucket(), new Bucket(), new Bucket()};
        var raw = mesh.quads();
        int previousModel = -1, material = 0;
        LodModel model = null;
        boolean water = false, glass = false;
        float emission = 0;
        while (raw.hasRemaining()) {
            long packed = raw.get();
            int modelId = (int) (packed >>> 26) & 65535;
            if (modelId != previousModel) {
                model = scene.models().get(modelId);
                if (model == null) throw new IllegalStateException("Voxy published mesh before its model");
                water = model.state().getFluidState().is(FluidTags.WATER) && model.state().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL;
                glass = !water && (model.flags() & 4) != 0;
                material = water ? snapshot.waterId() : snapshot.resolve(null, model.state(), glass);
                emission = model.state().getLightEmission() / 15f;
                previousModel = modelId;
            }
            LodQuad quad = LodQuad.decode(packed, model, mesh.level);
            int bucket = water ? 3 : glass ? 2 : quad.cutout() ? 1 : 0;
            int tint = (model.flags() & 2) != 0 && model.tint() >= 0
                    ? scene.colour(model.tint() + quad.biome()) : model.tint();
            if (!clipRealSections) {
                float u = component(quad, quad.axisU()), v = component(quad, quad.axisV());
                emit(buckets[bucket], quad, u, v, u + quad.width(), v + quad.height(), 1 << mesh.level,
                        material, textureSlot, tint, emission);
                continue;
            }
            float[] p = {quad.x(), quad.y(), quad.z()};
            int[] origin = {mesh.originX(), mesh.originY(), mesh.originZ()};
            float uEnd = p[quad.axisU()] + quad.width(), vEnd = p[quad.axisV()] + quad.height();
            for (float u = p[quad.axisU()]; u < uEnd;) {
                float un = clipRealSections ? Math.min(uEnd, nextBoundary(u, origin[quad.axisU()])) : uEnd;
                for (float v = p[quad.axisV()]; v < vEnd;) {
                    float vn = clipRealSections ? Math.min(vEnd, nextBoundary(v, origin[quad.axisV()])) : vEnd;
                    float[] center = p.clone();
                    center[quad.axisU()] = (u + un) * .5f;
                    center[quad.axisV()] = (v + vn) * .5f;
                    center[quad.axisNormal()] -= (quad.face() % 2 == 0 ? -1 : 1) * .01f;
                    boolean covered = clipRealSections && realSections.contains(RtTerrain.sectionKey(
                            Math.floorDiv((int) Math.floor(origin[0] + center[0]), 16),
                            Math.floorDiv((int) Math.floor(origin[1] + center[1]), 16),
                            Math.floorDiv((int) Math.floor(origin[2] + center[2]), 16)));
                    if (!covered) emit(buckets[bucket], quad, u, v, un, vn, 1 << mesh.level,
                            material, textureSlot, tint, emission);
                    v = vn;
                }
                u = un;
            }
        }
        FloatArrayList positions = new FloatArrayList(), materials = new FloatArrayList();
        IntArrayList indices = new IntArrayList();
        int[] counts = new int[4], bases = new int[4];
        for (int b = 0; b < 4; b++) {
            Bucket bucket = buckets[b];
            bases[b] = indices.size() / 3;
            counts[b] = bucket.indices.size() / 3;
            int vertexBase = positions.size() / 3;
            int[] elements = bucket.indices.elements();
            for (int i = 0; i < bucket.indices.size(); i++) indices.add(vertexBase + elements[i]);
            positions.addAll(bucket.positions); materials.addAll(bucket.materials);
        }
        var palette = RtVoxyAttributes.pack(materials.toFloatArray());
        return new RtTerrainMesher.PackedSection(positions.toFloatArray(), indices.toIntArray(), palette.records(),
                palette.indices(), counts, bases, new float[0], true);
    }

    private static float nextBoundary(float value, int origin) {
        return (float) ((Math.floor((origin + (double) value) / 16) + 1) * 16 - origin);
    }

    private static float component(LodQuad q, int axis) {
        return axis == 0 ? q.x() : axis == 1 ? q.y() : q.z();
    }

    static void appendPositions(FloatArrayList output, LodQuad q, float u, float v, float un, float vn) {
        appendPoint(output, q, u, v);
        appendPoint(output, q, un, v);
        appendPoint(output, q, un, vn);
        appendPoint(output, q, u, vn);
    }

    private static void appendPoint(FloatArrayList output, LodQuad q, float u, float v) {
        output.add(q.axisU() == 0 ? u : q.axisV() == 0 ? v : q.x());
        output.add(q.axisU() == 1 ? u : q.axisV() == 1 ? v : q.y());
        output.add(q.axisU() == 2 ? u : q.axisV() == 2 ? v : q.z());
    }

    private static void emit(Bucket bucket, LodQuad q, float u, float v, float un, float vn,
                             float scale, int material, int texture, int tint, float emission) {
        int base = bucket.positions.size() / 3;
        appendPositions(bucket.positions, q, u, v, un, vn);
        bucket.indices.add(base); bucket.indices.add(base + 1); bucket.indices.add(base + 2);
        bucket.indices.add(base); bucket.indices.add(base + 2); bucket.indices.add(base + 3);
        RtVoxyAttributes.append(bucket.materials, material, tint, q.model(), q.face(), q.tintMode(), texture,
                Integer.numberOfTrailingZeros((int)scale), emission);
    }
}
