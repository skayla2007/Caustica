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
        while (raw.hasRemaining()) {
            long packed = raw.get();
            LodModel model = scene.models().get((int) (packed >>> 26) & 65535);
            if (model == null) throw new IllegalStateException("Voxy published mesh before its model");
            LodQuad quad = LodQuad.decode(packed, model, mesh.level);
            boolean water = model.state().getFluidState().is(FluidTags.WATER) && model.state().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL;
            boolean glass = !water && (model.flags() & 4) != 0;
            int bucket = water ? 3 : glass ? 2 : quad.cutout() ? 1 : 0;
            int material = water ? snapshot.waterId() : snapshot.resolve(null, model.state(), glass);
            int tint = (model.flags() & 2) != 0 && model.tint() >= 0
                    ? scene.colour(model.tint() + quad.biome()) : model.tint();
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
                    if (!covered) emit(buckets[bucket], quad, p, u, v, un, vn, 1 << mesh.level,
                            material, textureSlot, tint, model.state().getLightEmission() / 15f);
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
            for (int i : bucket.indices) indices.add(vertexBase + i);
            positions.addAll(bucket.positions); materials.addAll(bucket.materials);
        }
        return new RtTerrainMesher.PackedSection(positions.toFloatArray(), indices.toIntArray(), new float[1],
                materials.toFloatArray(), counts, bases, new float[0], true);
    }

    private static float nextBoundary(float value, int origin) {
        return (float) ((Math.floor((origin + (double) value) / 16) + 1) * 16 - origin);
    }

    private static void emit(Bucket bucket, LodQuad q, float[] p, float u, float v, float un, float vn,
                             float scale, int material, int texture, int tint, float emission) {
        int base = bucket.positions.size() / 3;
        float[][] corners = {{u,v}, {un,v}, {un,vn}, {u,vn}};
        for (float[] corner : corners) {
            float[] point = p.clone();
            point[q.axisU()] = corner[0]; point[q.axisV()] = corner[1];
            bucket.positions.addElements(bucket.positions.size(), point);
        }
        int[] order = {0,1,2,0,2,3};
        for (int i : order) {
            bucket.indices.add(base + i);
        }
        RtVoxyAttributes.append(bucket.materials, material, tint, q.model(), q.face(), q.tintMode(), texture,
                Integer.numberOfTrailingZeros((int)scale), emission);
    }
}
