package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import me.cortex.voxy.api.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Optional provider adapter. The provider owns LOD selection and geometry; Caustica owns RT resources. */
public final class RtVoxy {
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("voxy");
    private static Backend backend;
    private RtVoxy() {}

    public static void initialize() {
        if (PRESENT) VoxyLodApi.registerExternalRenderer(dev.comfyfluffy.caustica.client.VanillaRenderController::rtRuntimeWorkRequested);
    }

    public static Object deviceHostLock() {
        return PRESENT ? VoxyLodApi.deviceHostLock() : new Object();
    }

    public static void frame(RtContext ctx, org.joml.Matrix4fc projectionView, int width, int height) {
        if (!PRESENT) return;
        if (backend == null) {
            if (VoxyLodApi.VERSION != 3) throw new IllegalStateException("Caustica requires Voxy LOD API 3; install both matching integration jars");
            backend = new Backend();
            CausticaMod.LOGGER.info("Voxy LOD API connected; Caustica owns ray-traced LOD rendering");
        }
        backend.frame(ctx, projectionView, width, height);
    }

    public static List<RtAccel.Instance> instances(RtTerrain terrain) {
        return backend != null && backend.table != null ? backend.instances : terrain.staticInstances();
    }

    public static long tableAddress(RtTerrain terrain) {
        return backend != null && backend.table != null ? backend.table.deviceAddress : terrain.tableAddress();
    }

    public static void clear(RtContext ctx) {
        if (backend != null) backend.clear(ctx);
    }

    private record Stamp(long key, long revision, long coverage) {
        @Override public int hashCode() {
            long value = key ^ Long.rotateLeft(revision, 23) ^ Long.rotateLeft(coverage, 41);
            value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
            value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
            value ^= value >>> 31;
            return (int)(value ^ (value >>> 32));
        }
    }
    private static final class Entry {
        final Stamp stamp;
        final LodMesh mesh;
        RtSectionBuilder.PreparedSection prepared;
        RtGpuExecutor.Build build;
        boolean ready;
        long lastNeeded;
        Entry(Stamp stamp, LodMesh mesh) { this.stamp = stamp; this.mesh = mesh; }
        void destroy() {
            if (prepared == null) return;
            prepared.blas().accel.destroy();
            prepared.uvs().destroy();
            prepared.material().destroy();
        }
    }
    private record Completion(long generation, Entry entry, RtGpuExecutor.Build build, Throwable failure) {}
    private record Request(Stamp stamp, LodMesh mesh, long covered) {}
    private record Prioritized(LodMesh mesh, double distance) {}
    private record CpuCompletion(long generation, Entry entry, RtTerrainMesher.PackedSection packed, Throwable failure) {}

    private static final class Backend {
        private final Map<Stamp, Entry> cache = new HashMap<>();
        private final Set<Stamp> pending = new HashSet<>();
        private final LongOpenHashSet busyKeys = new LongOpenHashSet();
        private final ConcurrentLinkedQueue<Completion> completed = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<CpuCompletion> converted = new ConcurrentLinkedQueue<>();
        private final ExecutorService converters = Executors.newFixedThreadPool(Math.min(4, Runtime.getRuntime().availableProcessors()), task -> {
            Thread thread = new Thread(task, "Caustica Voxy geometry conversion");
            thread.setDaemon(true);
            return thread;
        });
        private List<Entry> active = List.of();
        private List<RtAccel.Instance> instances = List.of();
        private RtBuffer table;
        private volatile long generation;
        private long epoch = -1, materialEpoch = -1, sourceTable;
        private int textureSlot = -1, originX, originY, originZ;
        private int reportedMeshes;
        private long frame;
        private static final boolean PROFILE = Boolean.getBoolean("caustica.voxy.profile");
        private long profileAt = System.nanoTime(), profileFrames, providerNs, prepareNs, selectionNs;
        private long dispatchedEmpty, dispatchedGeometry, preparedCount;
        private LodScene sortedScene;
        private List<LodMesh> sortedCandidates = List.of();
        private double sortedX, sortedY, sortedZ;
        private LodScene indexedScene;
        private LodHierarchy.Selection hierarchy;
        private final RtVoxySelectionGate selectionGate = new RtVoxySelectionGate();
        private long vanillaRevision = -1;
        private final Long2ObjectOpenHashMap<Entry> resolved = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<Stamp> plannedStamps = new Long2ObjectOpenHashMap<>();
        private final Set<Stamp> needed = new HashSet<>();
        private final ArrayDeque<Request> workQueue = new ArrayDeque<>();

        void frame(RtContext ctx, org.joml.Matrix4fc projectionView, int width, int height) {
            long profileStart = System.nanoTime();
            frame++;
            var mc = Minecraft.getInstance();
            RtTerrain terrain = RtTerrain.currentOrNull();
            if (mc.level == null || mc.player == null || terrain == null || !RtMaterialRegistry.INSTANCE.isReady()) {
                clear(ctx);
                return;
            }
            var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            var pos = camera != null && camera.pos != null ? camera.pos : mc.player.position();
            LodScene scene = VoxyLodApi.update(pos.x, pos.y, pos.z, projectionView, width, height);
            long afterProvider = System.nanoTime();
            if (scene == null) { clear(ctx); return; }
            int slot = RtEntityTextures.INSTANCE.slotForView(scene.atlasView());
            if (slot == 0) throw new IllegalStateException("No bindless texture slot for Voxy LOD atlas");
            long materials = RtMaterialRegistry.INSTANCE.epoch();
            if (scene.epoch() != epoch || materials != materialEpoch || slot != textureSlot) {
                clear(ctx);
                epoch = scene.epoch(); materialEpoch = materials; textureSlot = slot;
            }
            List<Entry> batch = new ArrayList<>();
            boolean readinessChanged = false;
            long prepareDeadline = System.nanoTime() + 8_000_000;
            for (int n = 0; n < 512 && System.nanoTime() < prepareDeadline; n++) {
                CpuCompletion cpu = converted.poll();
                if (cpu == null) break;
                if (cpu.generation() != generation) continue;
                Entry entry = cpu.entry();
                if (cpu.failure() != null) throw new IllegalStateException("Voxy triangle conversion failed", cpu.failure());
                if (cpu.packed().indices().length == 0) {
                    entry.ready = true;
                    readinessChanged = true;
                    resolveReady(entry);
                    pending.remove(entry.stamp);
                    busyKeys.remove(entry.mesh.key);
                } else {
                    entry.prepared = RtSectionBuilder.prepare(ctx, cpu.packed(), null, true, entry.mesh.key,
                            entry.mesh.originX(), entry.mesh.originY(), entry.mesh.originZ());
                    batch.add(entry);
                    preparedCount++;
                }
            }
            if (!batch.isEmpty()) submit(ctx, batch);
            Completion result;
            while ((result = completed.poll()) != null) {
                Entry entry = result.entry();
                if (result.generation() == generation) {
                    pending.remove(entry.stamp);
                    busyKeys.remove(entry.mesh.key);
                }
                if (result.failure() != null) {
                    ctx.gpuExecutor().retireUnpublished(() -> RtSectionBuilder.destroy(entry.prepared));
                    if (result.generation() == generation) cache.remove(entry.stamp);
                    throw new IllegalStateException("Voxy LOD BLAS build failed", result.failure());
                }
                entry.build = result.build(); entry.ready = true;
                if (result.generation() == generation) {
                    readinessChanged = true;
                    resolveReady(entry);
                }
                if (result.generation() != generation) ctx.gpuExecutor().retireUnpublished(entry::destroy);
            }
            long afterPrepare = System.nanoTime();
            var realSections = RtTerrain.readySectionSnapshot();
            boolean planChanged = selectionGate.refresh(scene, realSections);
            if (planChanged) {
                if (sortedScene != scene || Math.abs(sortedX-pos.x) + Math.abs(sortedY-pos.y) + Math.abs(sortedZ-pos.z) >= 8) {
                    var ordered = new ArrayList<Prioritized>(scene.ancestors().size() + scene.meshes().size());
                    for (LodMesh mesh : scene.ancestors()) ordered.add(new Prioritized(mesh, priority(mesh, pos.x, pos.y, pos.z)));
                    for (LodMesh mesh : scene.meshes()) ordered.add(new Prioritized(mesh, priority(mesh, pos.x, pos.y, pos.z)));
                    ordered.sort(Comparator.comparingDouble(Prioritized::distance));
                    sortedCandidates = ordered.stream().map(Prioritized::mesh).toList(); sortedScene = scene;
                    sortedX = pos.x; sortedY = pos.y; sortedZ = pos.z;
                }
                if (indexedScene != scene) { hierarchy = LodHierarchy.prepare(scene); indexedScene = scene; }
                resolved.clear(); plannedStamps.clear(); needed.clear(); workQueue.clear();
                for (Entry entry : active) resolved.put(entry.mesh.key, entry);
                Long2LongOpenHashMap coverage = coverageHashes(realSections);
                for (LodMesh mesh : sortedCandidates) {
                    long covered = coverage.get(mesh.key);
                    Stamp stamp = new Stamp(mesh.key, mesh.revision, covered);
                    plannedStamps.put(mesh.key, stamp);
                    needed.add(stamp);
                    Entry entry = cache.get(stamp);
                    boolean coveredParent = hierarchy.isCoveredParent(mesh.key);
                    if (entry == null && !coveredParent && mesh.quadCount() == 0) {
                        entry = new Entry(stamp, mesh); entry.ready = true; cache.put(stamp, entry); dispatchedEmpty++;
                    }
                    if (entry != null) {
                        entry.lastNeeded = frame;
                        if (entry.ready) resolved.put(mesh.key, entry);
                    } else if (!coveredParent) workQueue.addLast(new Request(stamp, mesh, covered));
                }
            }
            // Advance the saved queue directly; ready meshes do not consume dispatch work.
            for (int attempts = workQueue.size(); attempts > 0 && pending.size() < 512; attempts--) {
                Request request = workQueue.removeFirst();
                LodMesh mesh = request.mesh();
                if (hierarchy.isCoveredParent(mesh.key) || cache.containsKey(request.stamp())) continue;
                if (busyKeys.contains(mesh.key)) { workQueue.addLast(request); continue; }
                Entry entry = new Entry(request.stamp(), mesh);
                entry.lastNeeded = frame;
                cache.put(entry.stamp, entry); pending.add(entry.stamp); busyKeys.add(mesh.key);
                dispatchedGeometry++;
                long token = generation;
                var clipping = request.covered() == 0 ? it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET : realSections;
                var materialSnapshot = RtMaterialRegistry.INSTANCE.requireSnapshot();
                converters.execute(() -> {
                    if (token != generation) return;
                    RtTerrainMesher.PackedSection packed = null;
                    Throwable failure = null;
                    try { packed = RtVoxyMesh.convert(scene, mesh, slot, clipping, materialSnapshot); }
                    catch (Throwable t) { failure = t; }
                    synchronized (converted) {
                        if (token == generation) converted.add(new CpuCompletion(token, entry, packed, failure));
                    }
                });
            }
            boolean changed = false;
            if (planChanged || readinessChanged) {
                // The provider owns the hierarchy; readiness gates only the affected branches' publication.
                List<Entry> desired = new ArrayList<>();
                for (LodMesh mesh : hierarchy.readySelectionUnsorted(m -> resolved.containsKey(m.key),
                        () -> active.stream().map(e -> e.mesh).toList())) {
                    Entry entry = resolved.get(mesh.key);
                    if (entry.prepared != null) desired.add(entry);
                }
                changed = !active.equals(desired);
                if (changed) {
                    active = List.copyOf(desired);
                    for (Entry entry : active) ctx.gpuExecutor().markPublished(entry.build);
                    if (reportedMeshes == 0 || active.size() >= reportedMeshes + 32) {
                        int[] levels = new int[5];
                        for (Entry entry : active) levels[entry.mesh.level]++;
                        CausticaMod.LOGGER.info("Caustica RT published {} Voxy LOD meshes ({} selected, levels {}, pending {})",
                                active.size(), scene.meshes().size(), Arrays.toString(levels), pending.size());
                        reportedMeshes = active.size();
                    }
                }
                Set<Entry> activeSet = new HashSet<>(active);
                for (long key : hierarchy.coveredParents()) {
                    Entry entry = resolved.get(key);
                    if (entry != null && entry.ready && !activeSet.contains(entry)) {
                        ctx.gpuExecutor().retireAfterGraphics(ctx.gpuExecutor().latestGraphicsUse(), entry::destroy);
                        cache.remove(entry.stamp); resolved.remove(key);
                    }
                }
                if (planChanged || cache.size() > scene.meshes().size() + 512) {
                    var iterator = cache.entrySet().iterator();
                    while (iterator.hasNext()) {
                        var item = iterator.next();
                        Entry entry = item.getValue();
                        if (entry.ready && !needed.contains(item.getKey()) && !activeSet.contains(entry)
                                && (frame - entry.lastNeeded > 180 || cache.size() > scene.meshes().size() + 512)) {
                            ctx.gpuExecutor().retireAfterGraphics(ctx.gpuExecutor().latestGraphicsUse(), entry::destroy);
                            if (resolved.get(entry.mesh.key) == entry) resolved.remove(entry.mesh.key);
                            iterator.remove();
                        }
                    }
                }
                selectionGate.remember(scene, realSections);
            }
            if (changed || sourceTable != terrain.tableAddress()) rebuildTable(ctx, terrain);
            if (table != null && (vanillaRevision != terrain.instanceRevision()
                    || originX != terrain.blockX || originY != terrain.blockY || originZ != terrain.blockZ))
                rebuildInstances(terrain);
            if (PROFILE) {
                long now = System.nanoTime();
                profileFrames++; providerNs += afterProvider - profileStart;
                prepareNs += afterPrepare - afterProvider; selectionNs += now - afterPrepare;
                if (now - profileAt > 2_000_000_000L) {
                    long targetQuads = 0, ancestorQuads = 0, residentBytes = 0, blasBytes = 0;
                    int empty = 0, readyTargets = 0;
                    for (LodMesh m : scene.meshes()) {
                        int count = m.quadCount(); targetQuads += count; if (count == 0) empty++;
                        Entry target = cache.get(plannedStamps.get(m.key));
                        if (target != null && target.ready) readyTargets++;
                    }
                    for (LodMesh m : scene.ancestors()) ancestorQuads += m.quadCount();
                    for (Entry e : cache.values()) if (e.prepared != null) {
                        residentBytes += e.prepared.uvs().size + e.prepared.material().size;
                        blasBytes += e.prepared.blas().accel.allocatedBytes();
                    }
                    CausticaMod.LOGGER.info("Voxy RT profile fps={} cpuMs(provider/prepare/select)={}/{}/{} targets={} empty={} quads={} ancestors={} ancestorQuads={} cache={} residentAttributesMiB={} blasMiB={} active={} pending={} cpuReady={} gpuReady={} dispatched(empty/geometry)={}/{} prepared={} readyTargets={} unscheduled={}",
                        profileFrames * 1e9 / (now - profileAt), providerNs / (profileFrames * 1e6), prepareNs / (profileFrames * 1e6), selectionNs / (profileFrames * 1e6),
                        scene.meshes().size(), empty, targetQuads, scene.ancestors().size(), ancestorQuads, cache.size(), residentBytes / 1048576, blasBytes / 1048576, active.size(), pending.size(), converted.size(), completed.size(), dispatchedEmpty, dispatchedGeometry, preparedCount, readyTargets, workQueue.size());
                    profileAt = now; profileFrames = providerNs = prepareNs = selectionNs = dispatchedEmpty = dispatchedGeometry = preparedCount = 0;
                }
            }
        }

        private void resolveReady(Entry entry) {
            Stamp current = plannedStamps.get(entry.mesh.key);
            if (entry.stamp.equals(current) || !resolved.containsKey(entry.mesh.key)) resolved.put(entry.mesh.key, entry);
        }

        private static double priority(LodMesh mesh, double x, double y, double z) {
            double size = mesh.blockSize();
            double dx = Math.max(Math.max(mesh.originX() - x, x - mesh.originX() - size), 0);
            double dy = Math.max(Math.max(mesh.originY() - y, y - mesh.originY() - size), 0);
            double dz = Math.max(Math.max(mesh.originZ() - z, z - mesh.originZ() - size), 0);
            // Nearby ancestors establish coverage before refinement.
            return Math.sqrt(dx * dx + dy * dy + dz * dz) + (4 - mesh.level) * 32.0;
        }

        private void submit(RtContext ctx, List<Entry> entries) {
            long token = generation;
            var builds = entries.stream().map(e -> e.prepared.blas()).toList();
            ctx.gpuExecutor().submit(cmd -> {
                for (Entry entry : entries) RtSectionBuilder.recordUpload(cmd, entry.prepared);
                RtAccel.recordImmutableTerrainBuilds(ctx, cmd, builds);
            }, () -> {
                RtAccel.freeBlasScratch(builds);
                for (Entry entry : entries) { entry.prepared.releaseUpload(); entry.prepared.releaseBuildInputs(); }
            }, (build, failure) -> {
                if (failure == null && token == generation) compact(ctx, token, entries, build);
                else for (Entry entry : entries) finish(ctx, token, entry, build, failure);
            });
        }

        private void compact(RtContext ctx, long token, List<Entry> entries, RtGpuExecutor.Build build) {
            List<RtAccel.PreparedTerrainCompaction> operations = new ArrayList<>(entries.size());
            try {
                for (Entry entry : entries) operations.add(RtAccel.prepareTerrainCompaction(ctx, entry.prepared.blas()));
                // One completion boundary covers the entire immutable mesh batch.
                ctx.gpuExecutor().submit(cmd -> RtAccel.recordTerrainCompactions(ctx, cmd, operations), () -> {
                    for (var operation : operations) RtAccel.finishTerrainCompaction(operation);
                }, (copyBuild, failure) -> {
                    for (int i = 0; i < entries.size(); i++) {
                        Entry entry = entries.get(i);
                        var operation = operations.get(i);
                        if (failure == null) entry.prepared = entry.prepared.withBlas(operation.compacted());
                        else RtAccel.destroyTerrainCompaction(operation);
                        finish(ctx, token, entry, copyBuild, failure);
                    }
                    });
            } catch (Throwable failure) {
                for (var operation : operations) RtAccel.destroyTerrainCompaction(operation);
                for (Entry entry : entries) finish(ctx, token, entry, build, failure);
            }
        }

        private void finish(RtContext ctx, long token, Entry entry, RtGpuExecutor.Build build, Throwable failure) {
            synchronized (completed) {
                if (token != generation) {
                    if (failure == null) ctx.gpuExecutor().retireUnpublished(entry::destroy);
                    else ctx.gpuExecutor().retireUnpublished(() -> RtSectionBuilder.destroy(entry.prepared));
                } else completed.add(new Completion(token, entry, build, failure));
            }
        }

        private void rebuildTable(RtContext ctx, RtTerrain terrain) {
            RtBuffer old = table;
            if (active.isEmpty()) table = null;
            else {
                int offset = terrain.tableCapacity();
                table = ctx.createBuffer((offset + (long) active.size()) * 32, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "Voxy combined terrain table");
                MemoryUtil.memCopy(terrain.tableMappedAddress(), table.mapped, offset * 32L);
                for (int i = 0; i < active.size(); i++) {
                    var p = active.get(i).prepared;
                    long address = table.mapped + (offset + (long) i) * 32;
                    MemoryUtil.memPutLong(address, p.material().deviceAddress);
                    MemoryUtil.memPutLong(address + 8, p.uvs().deviceAddress | 1L);
                    for (int b = 0; b < 4; b++) MemoryUtil.memPutInt(address + 16 + b * 4L, p.triBase()[b]);
                }
                table.flush();
            }
            sourceTable = terrain.tableAddress();
            if (old != null) ctx.gpuExecutor().retireAfterGraphics(ctx.gpuExecutor().latestGraphicsUse(), old::destroy);
            rebuildInstances(terrain);
        }

        private void rebuildInstances(RtTerrain terrain) {
            var list = new ArrayList<>(terrain.staticInstances());
            int offset = terrain.tableCapacity();
            for (int i = 0; i < active.size(); i++) {
                var entry = active.get(i);
                list.add(new RtAccel.Instance(new float[]{1,0,0,entry.mesh.originX()-terrain.blockX,
                        0,1,0,entry.mesh.originY()-terrain.blockY,0,0,1,entry.mesh.originZ()-terrain.blockZ},
                        entry.prepared.blas().accel.deviceAddress, offset + i));
            }
            instances = list;
            vanillaRevision = terrain.instanceRevision();
            originX = terrain.blockX; originY = terrain.blockY; originZ = terrain.blockZ;
        }

        void clear(RtContext ctx) {
            synchronized (completed) {
                generation++;
                Completion result;
                while ((result = completed.poll()) != null) {
                    Entry entry = result.entry();
                    if (result.failure() == null) ctx.gpuExecutor().retireUnpublished(entry::destroy);
                    else ctx.gpuExecutor().retireUnpublished(() -> RtSectionBuilder.destroy(entry.prepared));
                    cache.remove(entry.stamp);
                }
            }
            synchronized (converted) { converted.clear(); }
            for (Entry entry : cache.values()) if (entry.ready)
                ctx.gpuExecutor().retireAfterGraphics(ctx.gpuExecutor().latestGraphicsUse(), entry::destroy);
            cache.clear(); pending.clear(); busyKeys.clear(); active = List.of(); instances = List.of();
            if (table != null) {
                RtBuffer old = table;
                ctx.gpuExecutor().retireAfterGraphics(ctx.gpuExecutor().latestGraphicsUse(), old::destroy);
                table = null;
            }
            sourceTable = 0; epoch = -1;
            reportedMeshes = 0;
            sortedScene = null; sortedCandidates = List.of();
            indexedScene = null; hierarchy = null;
            selectionGate.clear(); vanillaRevision = -1;
            resolved.clear(); plannedStamps.clear(); needed.clear(); workQueue.clear();
        }

        private static Long2LongOpenHashMap coverageHashes(it.unimi.dsi.fastutil.longs.LongSet sections) {
            Long2LongOpenHashMap result = new Long2LongOpenHashMap();
            for (long key : sections) {
                int x = (int) (key << 38 >> 38), z = (int) (key << 12 >> 38), y = (int) (key >> 52);
                long hash = key + 0x9e3779b97f4a7c15L;
                hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
                hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
                hash ^= hash >>> 31;
                for (int level = 0; level <= 4; level++) {
                    long node = me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(level,
                            Math.floorDiv(x, 2 << level), Math.floorDiv(y, 2 << level), Math.floorDiv(z, 2 << level));
                    result.put(node, result.get(node) ^ hash);
                }
            }
            return result;
        }
    }
}
