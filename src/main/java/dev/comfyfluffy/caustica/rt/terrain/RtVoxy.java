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
            if (VoxyLodApi.VERSION != 4) throw new IllegalStateException("Caustica requires Voxy LOD API 4; install both matching integration jars");
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
        boolean live = true;
        boolean published;
        RtAccel.Instance instance;
        long lastNeeded;
        long buildWorkingBytes;
        long buildTriangles;
        Entry(Stamp stamp, LodMesh mesh) { this.stamp = stamp; this.mesh = mesh; }
        RtAccel.Instance instance(int x, int y, int z, int index) {
            float dx = mesh.originX() - x, dy = mesh.originY() - y, dz = mesh.originZ() - z;
            float[] transform = instance == null ? null : instance.transform3x4();
            if (transform == null || transform[3] != dx || transform[7] != dy || transform[11] != dz)
                transform = new float[]{1,0,0,dx,0,1,0,dy,0,0,1,dz};
            long address = prepared.blas().accel.deviceAddress;
            if (instance == null || instance.transform3x4() != transform || instance.customIndex() != index
                    || instance.blasDeviceAddress() != address)
                instance = new RtAccel.Instance(transform, address, index);
            return instance;
        }
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
    private record SelectionResult(long generation, long retireRevision, LodHierarchy.Selection hierarchy,
                                   List<LodMesh> meshes, List<Entry> entries, List<Entry> drawable, List<Entry> unpublished,
                                   List<Entry> departing, Long2ObjectOpenHashMap<Entry> published, LodHierarchy.Coverage coverage) {}

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
        private long atlasView;
        private int reportedMeshes;
        private static final boolean PROFILE = Boolean.getBoolean("caustica.voxy.profile");
        private long profileAt = System.nanoTime(), profileFrames, providerNs, prepareNs, selectionNs;
        private long dispatchedEmpty, dispatchedGeometry, preparedCount;
        private dev.comfyfluffy.caustica.rt.RtMemoryBudget memoryBudget;
        private final java.util.concurrent.atomic.AtomicLong buildWorkingBytes = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong buildTriangles = new java.util.concurrent.atomic.AtomicLong();
        private final RtVoxyLoadBudget loadBudget = new RtVoxyLoadBudget();
        private long lastFrameTime, triangleLimit = RtVoxyLoadBudget.MAX_TRIANGLES;
        private long warmCacheLimit, nextPublication;
        private final List<Entry> warmEntries = new ArrayList<>();
        private long nextWarmTrim;
        private long retireRevision, nextEmptyTrim;
        private final ArrayDeque<Entry> emptyEntries = new ArrayDeque<>();
        private boolean publicationDirty;
        private List<LodMesh> publishedSelection = List.of();
        private final ExecutorService selector = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "Caustica Voxy ready selection"); thread.setDaemon(true); return thread;
        });
        private java.util.concurrent.CompletableFuture<SelectionResult> selecting;
        private SelectionResult readySelection;
        private LodHierarchy.Selection coveredSelection;
        private LodHierarchy.Coverage publishedCoverage;
        private Long2ObjectOpenHashMap<Entry> publishedEntries = new Long2ObjectOpenHashMap<>();
        private long staleCpuJobs, publications, plannedRequests;
        private long buildLimit = RtVoxyBuildBudget.MAX, nextBudgetRead;
        private LodScene indexedScene;
        private LodHierarchy.Selection hierarchy;
        private final RtVoxySelectionGate selectionGate = new RtVoxySelectionGate();
        private long vanillaRevision = -1;
        private Long2ObjectOpenHashMap<Entry> resolved = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<Stamp> plannedStamps = new Long2ObjectOpenHashMap<>();
        private final Set<Stamp> needed = new HashSet<>();
        private final ArrayDeque<Request> workQueue = new ArrayDeque<>();

        void frame(RtContext ctx, org.joml.Matrix4fc projectionView, int width, int height) {
            long profileStart = System.nanoTime();
            if (lastFrameTime != 0) triangleLimit = loadBudget.update(profileStart - lastFrameTime);
            lastFrameTime = profileStart;
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
            long materials = RtMaterialRegistry.INSTANCE.epoch();
            int slot;
            if (scene.epoch() == epoch && materials == materialEpoch && textureSlot > 0
                    && scene.atlasView() != atlasView) {
                var readers = new RtGpuExecutor.TrackedGraphicsUse();
                readers.mark(ctx.gpuExecutor().latestGraphicsUse());
                ctx.gpuExecutor().graphicsUseWaiter().await(readers);
                RtEntityTextures.INSTANCE.replaceView(textureSlot, atlasView, scene.atlasView());
                slot = textureSlot;
            } else slot = RtEntityTextures.INSTANCE.slotForView(scene.atlasView());
            if (slot == 0) throw new IllegalStateException("No bindless texture slot for Voxy LOD atlas");
            atlasView = scene.atlasView();
            if (scene.epoch() != epoch || materials != materialEpoch || slot != textureSlot) {
                clear(ctx);
                epoch = scene.epoch(); materialEpoch = materials; textureSlot = slot;
            }
            List<Entry> batch = new ArrayList<>();
            boolean readinessChanged = false;
            long budgetNow = System.nanoTime();
            if (budgetNow >= nextBudgetRead) {
                if (memoryBudget == null) memoryBudget = new dev.comfyfluffy.caustica.rt.RtMemoryBudget(ctx);
                var budget = memoryBudget.read();
                buildLimit = RtVoxyBuildBudget.limit(budget.localUsage(), budget.localBudget());
                warmCacheLimit = Math.min(1024L << 20, Math.max(0, (budget.localBudget() - budget.localUsage() - (128L << 20)) / 4));
                nextBudgetRead = budgetNow + 500_000_000L;
            }
            long prepareDeadline = System.nanoTime() + 2_000_000;
            long batchTriangles = 0;
            for (int n = 0; n < 512 && batch.size() < 32 && System.nanoTime() < prepareDeadline; n++) {
                CpuCompletion cpu = converted.peek();
                if (cpu == null) break;
                if (cpu.generation() == generation && cpu.failure() == null && !needed.contains(cpu.entry().stamp)) {
                    converted.poll();
                    Entry stale = cpu.entry();
                    cache.remove(stale.stamp); pending.remove(stale.stamp); busyKeys.remove(stale.mesh.key);
                    staleCpuJobs++;
                    continue;
                }
                long nextTriangles = cpu.failure() == null ? cpu.packed().indices().length / 3L : 0;
                if (cpu.generation() == generation && cpu.failure() == null && nextTriangles != 0
                        && !RtVoxyLoadBudget.canStart(buildTriangles.get(), batchTriangles, nextTriangles, triangleLimit)) break;
                if (cpu.generation() == generation && cpu.failure() == null && cpu.packed().indices().length != 0
                        && !RtVoxyBuildBudget.canStart(buildWorkingBytes.get(), RtVoxyBuildBudget.estimate(cpu.packed()), buildLimit)) break;
                converted.poll();
                if (cpu.generation() != generation) continue;
                Entry entry = cpu.entry();
                if (cpu.failure() != null) throw new IllegalStateException("Voxy triangle conversion failed", cpu.failure());
                if (cpu.packed().indices().length == 0) {
                    entry.ready = true;
                    emptyEntries.addLast(entry);
                    readinessChanged = true;
                    resolveReady(entry);
                    pending.remove(entry.stamp);
                    busyKeys.remove(entry.mesh.key);
                } else {
                    entry.prepared = RtSectionBuilder.prepare(ctx, cpu.packed(), null, true, entry.mesh.key,
                            entry.mesh.originX(), entry.mesh.originY(), entry.mesh.originZ());
                    var p = entry.prepared;
                    entry.buildWorkingBytes = p.positions().size + p.indices().size + p.uvs().size
                            + p.material().size + p.upload().size + p.blas().buildWorkingBytes()
                            + p.blas().accel.allocatedBytes();
                    buildWorkingBytes.addAndGet(entry.buildWorkingBytes);
                    entry.buildTriangles = nextTriangles;
                    batchTriangles += nextTriangles;
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
                    warmEntries.add(entry);
                }
                if (result.generation() != generation) ctx.gpuExecutor().retireUnpublished(entry::destroy);
            }
            long afterPrepare = System.nanoTime();
            var realSections = RtTerrain.readySectionSnapshot();
            boolean planChanged = selectionGate.refresh(scene, realSections);
            if (planChanged) {
                if (indexedScene != scene) { hierarchy = LodHierarchy.prepare(scene); indexedScene = scene; }
                resolved=publishedEntries.clone(); plannedStamps.clear(); needed.clear(); workQueue.clear();
                Long2LongOpenHashMap coverage = coverageHashes(realSections);
                var candidates = new ArrayList<LodMesh>(scene.ancestors().size() + scene.meshes().size());
                candidates.addAll(scene.ancestors()); candidates.addAll(scene.meshes());
                for (LodMesh mesh : candidates) {
                    long covered = coverage.get(mesh.key);
                    Stamp stamp = new Stamp(mesh.key, mesh.revision, covered);
                    plannedStamps.put(mesh.key, stamp);
                    needed.add(stamp);
                    Entry entry = cache.get(stamp);
                    if (entry == null && mesh.quadCount() == 0) {
                        entry = new Entry(stamp, mesh); entry.ready = true; cache.put(stamp, entry); dispatchedEmpty++;
                        emptyEntries.addLast(entry);
                    }
                    if (entry != null) {
                        entry.lastNeeded = profileStart;
                        if (entry.ready) resolved.put(mesh.key, entry);
                    }
                }
                // Sort only missing work; already resident fine geometry must not trigger ancestor builds.
                var missing = new ArrayList<Prioritized>();
                for (LodMesh mesh : candidates) if (!cache.containsKey(plannedStamps.get(mesh.key)) && !isCovered(mesh.key))
                    missing.add(new Prioritized(mesh, priority(mesh, pos.x, pos.y, pos.z)));
                missing.sort(Comparator.comparingDouble(Prioritized::distance));
                for (Prioritized work : missing) {
                    LodMesh mesh = work.mesh();
                    workQueue.addLast(new Request(plannedStamps.get(mesh.key), mesh, coverage.get(mesh.key)));
                }
                plannedRequests += missing.size();
                selectionGate.remember(scene, realSections);
            }
            publicationDirty |= planChanged || readinessChanged;
            if (selecting != null && selecting.isDone()) {
                SelectionResult selection = selecting.join();
                selecting = null;
                if (selection.generation() == generation && (selection.retireRevision() == retireRevision
                        || selection.entries().stream().allMatch(e -> e.live && e.ready))) {
                    readySelection = selection;
                    coveredSelection = selection.hierarchy();
                } else publicationDirty = true;
            }
            // Advance the saved queue directly; ready meshes do not consume dispatch work.
            for (int attempts = workQueue.size(); attempts > 0 && pending.size() < 512; attempts--) {
                Request request = workQueue.removeFirst();
                LodMesh mesh = request.mesh();
                if (isCovered(mesh.key) || cache.containsKey(request.stamp())) continue;
                if (busyKeys.contains(mesh.key)) { workQueue.addLast(request); continue; }
                Entry entry = new Entry(request.stamp(), mesh);
                entry.lastNeeded = profileStart;
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
            if (readySelection != null && System.nanoTime() >= nextPublication) {
                nextPublication = System.nanoTime() + 50_000_000L;
                // The provider owns the hierarchy; readiness gates only the affected branches' publication.
                List<Entry> desired = readySelection.drawable();
                changed = !active.equals(desired);
                publishedSelection = readySelection.meshes();
                publishedCoverage = readySelection.coverage();
                publishedEntries=readySelection.published();
                warmEntries.addAll(readySelection.departing());
                if (changed) {
                    active = desired;
                    publications++;
                    for (Entry entry : readySelection.unpublished()) if (!entry.published) {
                        ctx.gpuExecutor().markPublished(entry.build);
                        entry.published = true;
                    }
                    if (reportedMeshes == 0 || active.size() >= reportedMeshes + 32) {
                        int[] levels = new int[5];
                        for (Entry entry : active) levels[entry.mesh.level]++;
                        CausticaMod.LOGGER.info("Caustica RT published {} Voxy LOD meshes ({} selected, levels {}, pending {})",
                                active.size(), scene.meshes().size(), Arrays.toString(levels), pending.size());
                        reportedMeshes = active.size();
                    }
                }
                readySelection=null;
                trimWarm(ctx,System.nanoTime());
            }
            if (!warmEntries.isEmpty() && System.nanoTime() >= nextWarmTrim) trimWarm(ctx,System.nanoTime());
            if (System.nanoTime() >= nextEmptyTrim) trimEmpty(System.nanoTime());
            // Start the next snapshot only after publication. Its prior partition and departing list
            // then refer to the same active generation; no other publication can overtake this job.
            if (publicationDirty && selecting == null && readySelection == null && System.nanoTime() >= nextPublication) {
                publicationDirty=false;
                long token=generation, retired=retireRevision;
                var ready=resolved.clone();
                var priorEntries=publishedEntries;
                var prior=publishedSelection;
                var priorCoverage=publishedCoverage;
                var priorActive=active;
                selecting=java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    // A delayed publication can contain retained leaves absent from the newest graph.
                    for (Entry entry : priorEntries.values()) ready.putIfAbsent(entry.mesh.key,entry);
                    var index=LodHierarchy.prepare(scene);
                    var meshes=index.readySelectionUnsorted(m -> ready.containsKey(m.key),() -> prior,priorCoverage);
                    var entries=new ArrayList<Entry>(meshes.size());
                    var drawable=new ArrayList<Entry>(); var unpublished=new ArrayList<Entry>();
                    var actual=new ArrayList<LodMesh>(meshes.size());
                    var published=new Long2ObjectOpenHashMap<Entry>(meshes.size());
                    for (LodMesh mesh : meshes) {
                        Entry entry=ready.get(mesh.key);
                        if (entry==null) throw new IllegalStateException("Published Voxy coverage lost its ready mesh");
                        entries.add(entry); actual.add(entry.mesh); published.put(entry.mesh.key,entry);
                        if (entry.prepared!=null) { drawable.add(entry); if(!entry.published) unpublished.add(entry); }
                    }
                    var departing=new ArrayList<Entry>();
                    for (Entry entry : priorActive) if (published.get(entry.mesh.key)!=entry) departing.add(entry);
                    return new SelectionResult(token,retired,index,List.copyOf(actual),List.copyOf(entries),List.copyOf(drawable),
                            List.copyOf(unpublished),List.copyOf(departing),published,index.coverage(actual,priorCoverage));
                },selector);
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
                    if (memoryBudget == null) memoryBudget = new dev.comfyfluffy.caustica.rt.RtMemoryBudget(ctx);
                    var budget = memoryBudget.read();
                    CausticaMod.LOGGER.info("RT memory budget localUsageMiB={} localBudgetMiB={} nonLocalUsageMiB={} rtAllocatedMiB={} rtBlocksMiB={} rtAllocations={} lodBuildWorkingMiB={} lodBuildLimitMiB={}",
                            budget.localUsage()/1048576, budget.localBudget()/1048576, budget.nonLocalUsage()/1048576,
                            budget.rtAllocated()/1048576, budget.rtBlocks()/1048576, budget.rtAllocations(),
                            buildWorkingBytes.get()/1048576, buildLimit/1048576);
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
                    CausticaMod.LOGGER.info("Voxy RT load budget triangles={} inFlightTriangles={} publications={} plannedRequests={} staleCpuJobs={} warmLimitMiB={}",
                            triangleLimit, buildTriangles.get(), publications, plannedRequests, staleCpuJobs, warmCacheLimit / 1048576);
                    publications = plannedRequests = staleCpuJobs = 0;
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
            for (Entry entry : entries) buildTriangles.addAndGet(entry.buildTriangles);
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

        private boolean isCovered(long key) {
            return hierarchy != null && hierarchy.coveredBy(coveredSelection,key);
        }

        private static long residentBytes(Entry entry) {
            return entry.prepared.uvs().size + entry.prepared.material().size + entry.prepared.blas().accel.allocatedBytes();
        }

        private void trimWarm(RtContext ctx, long now) {
            nextWarmTrim=now+500_000_000L;
            warmEntries.removeIf(e -> !e.live || publishedEntries.get(e.mesh.key) == e);
            warmEntries.sort(Comparator.comparingLong(e -> e.lastNeeded));
            long bytes=0;
            for (Entry entry : warmEntries) if (!needed.contains(entry.stamp) || isCovered(entry.mesh.key)) bytes+=residentBytes(entry);
            var iterator=warmEntries.iterator();
            while (iterator.hasNext()) {
                Entry entry=iterator.next();
                if (needed.contains(entry.stamp) && !isCovered(entry.mesh.key)) continue;
                if (bytes > warmCacheLimit || now-entry.lastNeeded > 10_000_000_000L) {
                    bytes-=residentBytes(entry);
                    entry.live=false; retireRevision++;
                    ctx.gpuExecutor().retireAfterGraphics(ctx.gpuExecutor().latestGraphicsUse(),entry::destroy);
                    cache.remove(entry.stamp);
                    if (resolved.get(entry.mesh.key)==entry) resolved.remove(entry.mesh.key);
                    iterator.remove();
                }
            }
        }

        private void trimEmpty(long now) {
            nextEmptyTrim=now+500_000_000L;
            for (int n=Math.min(8192,emptyEntries.size());n>0;n--) {
                Entry entry=emptyEntries.removeFirst();
                if (!entry.live) continue;
                if (emptyEntries.size()>=65536 && !needed.contains(entry.stamp) && publishedEntries.get(entry.mesh.key)!=entry) {
                    entry.live=false; retireRevision++; cache.remove(entry.stamp);
                    if (resolved.get(entry.mesh.key)==entry) resolved.remove(entry.mesh.key);
                } else emptyEntries.addLast(entry);
            }
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
            buildWorkingBytes.addAndGet(-entry.buildWorkingBytes);
            buildTriangles.addAndGet(-entry.buildTriangles);
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
                    // Low address bits tag analytic LOD UVs and direct versus palette attributes.
                    MemoryUtil.memPutLong(address + 8, p.uvs().deviceAddress | (p.uvs().size == 4 ? 3L : 1L));
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
                list.add(entry.instance(terrain.blockX, terrain.blockY, terrain.blockZ, offset + i));
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
            for (Entry entry : cache.values()) {
                entry.live=false;
                if (entry.ready) ctx.gpuExecutor().retireAfterGraphics(ctx.gpuExecutor().latestGraphicsUse(), entry::destroy);
            }
            cache.clear(); pending.clear(); busyKeys.clear(); active = List.of(); instances = List.of();
            if (table != null) {
                RtBuffer old = table;
                ctx.gpuExecutor().retireAfterGraphics(ctx.gpuExecutor().latestGraphicsUse(), old::destroy);
                table = null;
            }
            sourceTable = 0; epoch = -1;
            reportedMeshes = 0;
            indexedScene = null; hierarchy = null;
            selectionGate.clear(); vanillaRevision = -1;
            resolved.clear(); plannedStamps.clear(); needed.clear(); workQueue.clear();
            publishedSelection = List.of(); publishedCoverage = null; publishedEntries=new Long2ObjectOpenHashMap<>();
            // Background selection reads immutable snapshots only; an old generation cannot publish GPU resources.
            selecting = null; readySelection = null; coveredSelection = null;
            publicationDirty = false; nextPublication = 0;
            warmEntries.clear(); nextWarmTrim=0;
            emptyEntries.clear(); nextEmptyTrim=0;
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
