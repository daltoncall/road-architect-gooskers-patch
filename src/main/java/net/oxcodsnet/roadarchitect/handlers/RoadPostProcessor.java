package net.oxcodsnet.roadarchitect.handlers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.oxcodsnet.roadarchitect.api.addon.RoadAddons;
import net.oxcodsnet.roadarchitect.handlers.GooskerRoadBackfill;
import net.oxcodsnet.roadarchitect.handlers.RoadPipelineController;
import net.oxcodsnet.roadarchitect.storage.PathStorage;
import net.oxcodsnet.roadarchitect.util.AsyncExecutor;
import net.oxcodsnet.roadarchitect.util.CacheManager;
import net.oxcodsnet.roadarchitect.util.DebugLog;
import net.oxcodsnet.roadarchitect.util.GooskerPathCoalescer;
import net.oxcodsnet.roadarchitect.util.profiler.PipelineProfiler;
import net.oxcodsnet.roadarchitect.worldgen.RoadFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoadPostProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)("roadarchitect/" + RoadPostProcessor.class.getSimpleName()));
    private static final int TOLERANCE_BLOCKS = 45;
    private static final int ANGLE_THRESHOLD_DEG = 35;
    private static final int TAIL_ANGLE_MAX_DEG = 35;
    private static final double AVG_DIST_FACTOR = 1.5;
    private static final double SCORE_LIMIT = 50.0;
    private static final int MAX_ITER = 5;
    private static final boolean UNTIL_STABLE = true;
    private static final int TRIM_RADIUS_L1 = 50;
    private static final int SMOOTH_MEDIAN_WINDOW = 5;
    private static final int SMOOTH_GRAD_MAX = 1;
    private static final int SMOOTH_PASSES = 2;
    private static final int DESPIKE_DELTA = 2;
    private static final boolean LOG_GRAD_CLAMP = true;
    /** Only one post-processing job may own a world's path snapshot at a time. */
    private static final Set<ServerLevel> ACTIVE_WORLDS = ConcurrentHashMap.newKeySet();

    private RoadPostProcessor() {
    }

    private static <T> T first(List<T> list) {
        return list.get(0);
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }

    public static void onStartWorldTick(ServerLevel world) {
        if (world.isClientSide()) {
            return;
        }
        if (!RoadPipelineController.isDimensionEnabled((ResourceKey<Level>)world.dimension())) {
            return;
        }
        RoadPostProcessor.processPending(world);
    }

    private static int manhattanXZ(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }

    private static List<BlockPos> trimByManhattan(List<BlockPos> path) {
        int j;
        int i;
        if (path == null || path.size() < 2) {
            return path;
        }
        BlockPos start0 = RoadPostProcessor.first(path);
        BlockPos end0 = RoadPostProcessor.last(path);
        for (i = 0; i < path.size() && RoadPostProcessor.manhattanXZ(path.get(i), start0) <= 50; ++i) {
        }
        for (j = path.size() - 1; j >= 0 && RoadPostProcessor.manhattanXZ(path.get(j), end0) <= 50; --j) {
        }
        if (i <= 0 && j >= path.size() - 1) {
            return path;
        }
        if (j - i + 1 < 2) {
            return path;
        }
        return new ArrayList<BlockPos>(path.subList(i, j + 1));
    }

    private static List<BlockPos> refine(ServerLevel world, List<BlockPos> verts) {
        if (verts.isEmpty()) {
            return List.of();
        }
        ChunkGenerator generator = world.getChunkSource().getGenerator();
        RandomState noiseConfig = world.getChunkSource().randomState();
        HashMap<Long, Integer> resolvedHeights = new HashMap<Long, Integer>();
        ArrayList<BlockPos> out = new ArrayList<BlockPos>(verts.size() * 4);
        for (int i = 0; i < verts.size() - 1; ++i) {
            BlockPos a = verts.get(i);
            BlockPos b = verts.get(i + 1);
            out.add(RoadPostProcessor.adjustToGround(world, generator, noiseConfig, resolvedHeights, a));
            RoadPostProcessor.interpolate(world, generator, noiseConfig, a, b, resolvedHeights, out);
        }
        out.add(RoadPostProcessor.adjustToGround(world, generator, noiseConfig, resolvedHeights, RoadPostProcessor.last(verts)));
        return out;
    }

    private static void interpolate(ServerLevel world, ChunkGenerator generator, RandomState noiseConfig, BlockPos a, BlockPos b, Map<Long, Integer> resolvedHeights, List<BlockPos> out) {
        int dx = Integer.signum(b.getX() - a.getX());
        int dz = Integer.signum(b.getZ() - a.getZ());
        int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
        for (int i = 1; i < steps; ++i) {
            int nx = a.getX() + dx * i;
            int nz = a.getZ() + dz * i;
            int ny = RoadPostProcessor.resolveSurfaceHeight(world, generator, noiseConfig, resolvedHeights, nx, nz) - 1;
            out.add(new BlockPos(nx, ny, nz));
        }
    }

    private static BlockPos adjustToGround(ServerLevel world, ChunkGenerator generator, RandomState noiseConfig, Map<Long, Integer> resolvedHeights, BlockPos pos) {
        int surface = RoadPostProcessor.resolveSurfaceHeight(world, generator, noiseConfig, resolvedHeights, pos.getX(), pos.getZ());
        return new BlockPos(pos.getX(), surface - 1, pos.getZ());
    }

    private static int resolveSurfaceHeight(ServerLevel world, ChunkGenerator generator, RandomState noiseConfig, Map<Long, Integer> resolvedHeights, int x, int z) {
        int bottomGuard;
        long key = CacheManager.hash(x, z);
        Integer cached = resolvedHeights.get(key);
        if (cached != null) {
            return cached;
        }
        OptionalInt prepared = RoadFeature.lookupPreparedSurface(x, z);
        int resolved = prepared.isPresent() ? prepared.getAsInt() : CacheManager.getHeight(world, x, z);
        // Prefer the generator's natural surface. Live heightmaps can include a
        // tree canopy or generated building roof and must not become a road Y.
        if (generator != null && noiseConfig != null) {
            try {
                int generated = generator.getBaseHeight(
                        x, z, Heightmap.Types.WORLD_SURFACE_WG, world, noiseConfig);
                if (generated > world.getMinBuildHeight()) {
                    resolved = generated;
                }
            } catch (Throwable ignored) {
                // Keep the cached terrain height when a modded generator cannot
                // answer this query safely.
            }
        }
        if (resolved < (bottomGuard = world.getMinBuildHeight() + 1)) {
            resolved = bottomGuard;
        }
        resolvedHeights.put(key, resolved);
        return resolved;
    }

    private static NormalizeResult normalizeHeights(List<BlockPos> refined) {
        int i;
        if (refined.size() < 3) {
            return new NormalizeResult(refined, 0, 0);
        }
        int n = refined.size();
        int[] y = new int[n];
        for (int i2 = 0; i2 < n; ++i2) {
            y[i2] = refined.get(i2).getY();
        }
        int spikes = 0;
        int clamps = 0;
        for (int pass = 0; pass < 2; ++pass) {
            int clamped;
            int hi;
            int r = 2;
            int[] tmp = Arrays.copyOf(y, n);
            int[] win = new int[5];
            for (int i3 = 0; i3 < n; ++i3) {
                int s = Math.max(0, i3 - r);
                int e = Math.min(n - 1, i3 + r);
                int k = 0;
                for (int j = s; j <= e; ++j) {
                    win[k++] = y[j];
                }
                while (k < win.length) {
                    win[k] = i3 < r ? y[0] : y[n - 1];
                    ++k;
                }
                Arrays.sort(win);
                tmp[i3] = win[win.length / 2];
            }
            y = tmp;
            for (i = 1; i < n; ++i) {
                int lo = y[i - 1] - 1;
                hi = y[i - 1] + 1;
                int old = y[i];
                clamped = Math.max(lo, Math.min(hi, old));
                if (clamped == old) continue;
                ++clamps;
                BlockPos p = refined.get(i);
                DebugLog.info(LOGGER, "[PostProcess] Clamp\u2191 at {}: {} -> {} (ref={})", p, old, clamped, y[i - 1]);
                y[i] = clamped;
            }
            for (i = n - 2; i >= 0; --i) {
                int lo = y[i + 1] - 1;
                hi = y[i + 1] + 1;
                int old = y[i];
                clamped = Math.max(lo, Math.min(hi, old));
                if (clamped == old) continue;
                ++clamps;
                BlockPos p = refined.get(i);
                DebugLog.info(LOGGER, "[PostProcess] Clamp\u2193 at {}: {} -> {} (ref={})", p, old, clamped, y[i + 1]);
                y[i] = clamped;
            }
            for (i = 1; i < n - 1; ++i) {
                int neu;
                int a = y[i - 1];
                int b = y[i + 1];
                int m = Math.max(a, b);
                if (y[i] - m < 2) continue;
                BlockPos p = refined.get(i);
                int old = y[i];
                y[i] = neu = (a + b) / 2;
                ++spikes;
                LOGGER.warn("[PostProcess] \u0421\u0440\u0435\u0437\u0430\u043d \u043f\u0438\u043a \u0432\u044b\u0441\u043e\u0442\u044b \u0432 {}: {} -> {} (\u0441\u043e\u0441\u0435\u0434\u0438: {}/{})", new Object[]{p, old, neu, a, b});
            }
        }
        ArrayList<BlockPos> out = new ArrayList<BlockPos>(n);
        for (i = 0; i < n; ++i) {
            BlockPos p = refined.get(i);
            out.add(new BlockPos(p.getX(), y[i], p.getZ()));
        }
        return new NormalizeResult(out, spikes, clamps);
    }

    public static void processPending(ServerLevel world) {
        if (ACTIVE_WORLDS.contains(world)) {
            return;
        }
        PathStorage storage = PathStorage.get(world);
        PipelineProfiler.increment("postprocess.invocations");
        int examined = 0;
        for (Map.Entry<String, PathStorage.Status> e : storage.allStatuses().entrySet()) {
            ++examined;
            if (e.getValue() != PathStorage.Status.PENDING) continue;
            RoadPostProcessor.schedule(world, storage, e.getKey());
            PipelineProfiler.increment("postprocess.entries_scheduled");
            break;
        }
        PipelineProfiler.recordValue("postprocess.entries_examined", examined);
    }

    private static void processChunk(ServerLevel world, ChunkPos chunk) {
        PathStorage storage = PathStorage.get(world);
        for (String key : storage.getPendingForChunk(chunk)) {
            RoadPostProcessor.schedule(world, storage, key);
        }
    }

    private static void schedule(ServerLevel world, PathStorage storage, String baseKey) {
        if (!ACTIVE_WORLDS.add(world)) {
            return;
        }
        if (!storage.tryMarkProcessing(baseKey)) {
            ACTIVE_WORLDS.remove(world);
            return;
        }
        PipelineProfiler.increment("postprocess.jobs_scheduled");
        List<BlockPos> baseRawInitial = storage.getPath(baseKey);
        Map<String, PathStorage.Status> statusSnapshot = new HashMap<>(storage.allStatuses());
        Map<String, List<BlockPos>> pathSnapshot = new HashMap<>();
        for (String key : statusSnapshot.keySet()) {
            pathSnapshot.put(key, List.copyOf(storage.getPath(key)));
        }
        statusSnapshot.put(baseKey, PathStorage.Status.PROCESSING);
        AsyncExecutor.execute(() -> {
            PipelineProfiler.increment("postprocess.jobs_started");
            PipelineProfiler.Section section = PipelineProfiler.openSection("postprocess.job");
            try {
                String activeKey = baseKey;
                List<BlockPos> activeRaw = new ArrayList<BlockPos>(baseRawInitial);
                activeRaw = RoadPostProcessor.trimByManhattan(activeRaw);
                HashSet<String> partnersMarked = new HashSet<String>();
                HashSet<String> becameReady = new HashSet<String>();
                HashMap<String, List<BlockPos>> toBuild = new HashMap<String, List<BlockPos>>();
                try {
                    boolean changed;
                    int iter = 0;
                    do {
                        changed = false;
                        ++iter;
                        MergeCandidate cand = RoadPostProcessor.findBestParallelPartner(statusSnapshot, pathSnapshot, activeKey, activeRaw);
                        if (cand == null) break;
                        if (statusSnapshot.get(cand.otherKey) != PathStorage.Status.PENDING) continue;
                        statusSnapshot.put(cand.otherKey, PathStorage.Status.PROCESSING);
                        partnersMarked.add(cand.otherKey);
                        List<BlockPos> otherRaw = pathSnapshot.getOrDefault(cand.otherKey, List.of());
                        otherRaw = RoadPostProcessor.trimByManhattan(otherRaw);
                        Convergence conv = RoadPostProcessor.findConvergence(activeRaw, otherRaw);
                        if (conv == null) {
                            statusSnapshot.put(cand.otherKey, PathStorage.Status.PENDING);
                            partnersMarked.remove(cand.otherKey);
                            continue;
                        }
                        BuildResult br = RoadPostProcessor.buildY(world, activeKey, activeRaw, cand.otherKey, otherRaw, conv);
                        for (Map.Entry<String, List<BlockPos>> leg : br.legsRaw.entrySet()) {
                            List<BlockPos> refined = RoadPostProcessor.refine(world, leg.getValue());
                            NormalizeResult nr = RoadPostProcessor.normalizeHeights(refined);
                            toBuild.put(leg.getKey(), nr.path());
                            if (!nr.path().isEmpty()) {
                                BlockPos s = RoadPostProcessor.first(nr.path());
                                BlockPos t = RoadPostProcessor.last(nr.path());
                                DebugLog.info(LOGGER, "[PostProcess] READY (leg) key={} points={}, spikesCut={}, gradClamped={}, start={}, end={}", leg.getKey(), nr.path().size(), nr.spikesCut(), nr.gradClamped(), s, t);
                            }
                            becameReady.add(leg.getKey());
                        }
                        List<BlockPos> trunkRefined = RoadPostProcessor.refine(world, br.trunkRaw.path);
                        NormalizeResult trunkNR = RoadPostProcessor.normalizeHeights(trunkRefined);
                        toBuild.put(br.trunkRaw.key, trunkNR.path());
                        if (!trunkNR.path().isEmpty()) {
                            BlockPos s = RoadPostProcessor.first(trunkNR.path());
                            BlockPos t = RoadPostProcessor.last(trunkNR.path());
                            DebugLog.info(LOGGER, "[PostProcess] READY (trunk) key={} points={}, spikesCut={}, gradClamped={}, start={}, end={}", br.trunkRaw.key, trunkNR.path().size(), trunkNR.spikesCut(), trunkNR.gradClamped(), s, t);
                        }
                        becameReady.add(br.trunkRaw.key);
                        activeKey = br.trunkRaw.key;
                        activeRaw = br.trunkRaw.path;
                        changed = true;
                    } while (iter < 5 && (changed || iter == 1));
                    if (toBuild.isEmpty()) {
                        List<BlockPos> refined = RoadPostProcessor.refine(world, activeRaw);
                        NormalizeResult nr = RoadPostProcessor.normalizeHeights(refined);
                        toBuild.put(activeKey, nr.path());
                        if (!nr.path().isEmpty()) {
                            BlockPos s = RoadPostProcessor.first(nr.path());
                            BlockPos t = RoadPostProcessor.last(nr.path());
                            DebugLog.info(LOGGER, "[PostProcess] READY (single) key={} points={}, spikesCut={}, gradClamped={}, start={}, end={}", activeKey, nr.path().size(), nr.spikesCut(), nr.gradClamped(), s, t);
                        }
                        becameReady.add(activeKey);
                    }
                    Map<String, List<BlockPos>> completedPaths = new HashMap<>(toBuild);
                    Set<String> completedKeys = new HashSet<>(becameReady);
                    Set<String> reservedPartners = new HashSet<>(partnersMarked);
                    world.getServer().execute(() -> {
                        try {
                            for (Map.Entry<String, List<BlockPos>> ready : completedPaths.entrySet()) {
                                storage.updatePath(ready.getKey(), ready.getValue(), PathStorage.Status.READY);
                                RoadAddons.onPathReady(world, ready.getKey(), ready.getValue());
                            }
                            GooskerPathCoalescer.coalesceReadyPaths(storage, completedPaths);
                            GooskerRoadBackfill.queueSegmentsAndEnqueue(world, completedPaths);
                            for (String partner : reservedPartners) {
                                if (!completedKeys.contains(partner)) {
                                    storage.setStatus(partner, PathStorage.Status.PENDING);
                                }
                            }
                            PipelineProfiler.recordValue("postprocess.paths_completed", completedKeys.size());
                        } finally {
                            ACTIVE_WORLDS.remove(world);
                        }
                    });
                }
                catch (Exception ex) {
                    LOGGER.error("Post-processing failed for {}", (Object)baseKey, (Object)ex);
                    Set<String> reservedPartners = new HashSet<>(partnersMarked);
                    world.getServer().execute(() -> {
                        try {
                            storage.setStatus(baseKey, PathStorage.Status.FAILED);
                            for (String p : reservedPartners) {
                                storage.setStatus(p, PathStorage.Status.PENDING);
                            }
                            PipelineProfiler.increment("postprocess.jobs_failed");
                        } finally {
                            ACTIVE_WORLDS.remove(world);
                        }
                    });
                    return;
                }
            }
            finally {
                if (section != null) {
                    section.close();
                }
            }
        });
    }

    private static MergeCandidate findBestParallelPartner(Map<String, PathStorage.Status> statuses,
                                                           Map<String, List<BlockPos>> paths,
                                                           String baseKey, List<BlockPos> baseRaw) {
        AABB bbBase = AABB.of(baseRaw).inflate(90);
        double bestScore = Double.POSITIVE_INFINITY;
        String bestKey = null;
        for (Map.Entry<String, PathStorage.Status> e : statuses.entrySet()) {
            double score;
            double minDist;
            double angle;
            AABB bbOther;
            List<BlockPos> otherRaw;
            String otherKey;
            if (e.getValue() != PathStorage.Status.PENDING || (otherKey = e.getKey()).equals(baseKey) || (otherRaw = paths.getOrDefault(otherKey, List.of())).size() < 2 || !bbBase.intersectsInflated(bbOther = AABB.of(otherRaw)) || (angle = RoadPostProcessor.angleDeg(RoadPostProcessor.dir(baseRaw), RoadPostProcessor.dir(otherRaw))) >= 35.0 || (minDist = RoadPostProcessor.minPointToPointDist(baseRaw, otherRaw)) >= 45.0 || !((score = angle + minDist * 0.5) < bestScore)) continue;
            bestScore = score;
            bestKey = otherKey;
        }
        return bestKey == null ? null : new MergeCandidate(bestKey, bestScore);
    }

    private static Convergence findConvergence(List<BlockPos> a, List<BlockPos> b) {
        int n = a.size();
        int m = b.size();
        if (n < 3 || m < 3) {
            return null;
        }
        double bestScore = Double.POSITIVE_INFINITY;
        int bestI = -1;
        int bestJ = -1;
        for (int i = 1; i < n - 1; ++i) {
            BlockPos ai = a.get(i);
            int jBest = -1;
            double dMin = Double.POSITIVE_INFINITY;
            for (int j = 1; j < m - 1; ++j) {
                double d = RoadPostProcessor.hypot2D(ai, b.get(j));
                if (!(d < dMin)) continue;
                dMin = d;
                jBest = j;
            }
            if (jBest <= 0 || dMin >= 90.0) continue;
            List<BlockPos> tailA = a.subList(i, n);
            List<BlockPos> tailB = b.subList(jBest, m);
            if (tailA.size() < 2 || tailB.size() < 2) continue;
            double angTail = RoadPostProcessor.angleDeg(RoadPostProcessor.dir(tailA), RoadPostProcessor.dir(tailB));
            double avgDist = RoadPostProcessor.minPointToPointDist(tailA, tailB);
            double score = angTail + avgDist / 10.0;
            if (!(angTail < 35.0) || !(avgDist < 67.5) || !(score < bestScore)) continue;
            bestScore = score;
            bestI = i;
            bestJ = jBest;
        }
        if (bestScore >= 50.0 || bestI < 0) {
            return null;
        }
        return new Convergence(bestI, bestJ);
    }

    private static BuildResult buildY(ServerLevel world, String keyA, List<BlockPos> a, String keyB, List<BlockPos> b, Convergence conv) {
        String chosenKey;
        List<BlockPos> chosenTail;
        BlockPos pa = a.get(conv.i);
        BlockPos pb = b.get(conv.j);
        int jx = (int)Math.round((double)(pa.getX() + pb.getX()) / 2.0);
        int jz = (int)Math.round((double)(pa.getZ() + pb.getZ()) / 2.0);
        ChunkGenerator generator = world.getChunkSource().getGenerator();
        RandomState noiseConfig = world.getChunkSource().randomState();
        HashMap<Long, Integer> resolvedHeights = new HashMap<Long, Integer>();
        BlockPos J = RoadPostProcessor.adjustToGround(world, generator, noiseConfig, resolvedHeights, new BlockPos(jx, pa.getY(), jz));
        ArrayList<BlockPos> legA = new ArrayList<BlockPos>(a.subList(0, conv.i + 1));
        legA.set(legA.size() - 1, J);
        ArrayList<BlockPos> legB = new ArrayList<BlockPos>(b.subList(0, conv.j + 1));
        legB.set(legB.size() - 1, J);
        List<BlockPos> tailA = a.subList(conv.i, a.size());
        List<BlockPos> tailB = b.subList(conv.j, b.size());
        if (tailA.size() > tailB.size() || tailA.size() == tailB.size() && conv.i <= conv.j) {
            chosenTail = tailA;
            chosenKey = keyA;
        } else {
            chosenTail = tailB;
            chosenKey = keyB;
        }
        ArrayList<BlockPos> trunk = new ArrayList<BlockPos>(chosenTail.size());
        trunk.add(J);
        trunk.addAll(chosenTail.subList(1, chosenTail.size()));
        String trunkKey = chosenKey + "#J@" + jx + "," + jz;
        HashMap<String, List<BlockPos>> legs = new HashMap<String, List<BlockPos>>();
        legs.put(keyA, legA);
        legs.put(keyB, legB);
        return new BuildResult(legs, new Trunk(trunkKey, trunk));
    }

    private static double angleDeg(int[] v1, int[] v2) {
        double n1 = Math.hypot(v1[0], v1[1]);
        double n2 = Math.hypot(v2[0], v2[1]);
        if (n1 == 0.0 || n2 == 0.0) {
            return 180.0;
        }
        double dot = (double)(v1[0] * v2[0] + v1[1] * v2[1]) / (n1 * n2);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        double ang = Math.toDegrees(Math.acos(dot));
        return Math.min(ang, 180.0 - ang);
    }

    private static int[] dir(List<BlockPos> pts) {
        BlockPos s = RoadPostProcessor.first(pts);
        BlockPos e = RoadPostProcessor.last(pts);
        return new int[]{e.getX() - s.getX(), e.getZ() - s.getZ()};
    }

    private static double minPointToPointDist(List<BlockPos> a, List<BlockPos> b) {
        double min = Double.POSITIVE_INFINITY;
        for (BlockPos pa : a) {
            for (BlockPos pb : b) {
                double d = RoadPostProcessor.hypot2D(pa, pb);
                if (!(d < min)) continue;
                min = d;
            }
        }
        return min;
    }

    private static double hypot2D(BlockPos p1, BlockPos p2) {
        int dx = p1.getX() - p2.getX();
        int dz = p1.getZ() - p2.getZ();
        return Math.hypot(dx, dz);
    }

    private record NormalizeResult(List<BlockPos> path, int spikesCut, int gradClamped) {
    }

    private record AABB(int x1, int z1, int x2, int z2) {
        static AABB of(List<BlockPos> pts) {
            int minX = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (BlockPos p : pts) {
                if (p.getX() < minX) {
                    minX = p.getX();
                }
                if (p.getZ() < minZ) {
                    minZ = p.getZ();
                }
                if (p.getX() > maxX) {
                    maxX = p.getX();
                }
                if (p.getZ() <= maxZ) continue;
                maxZ = p.getZ();
            }
            return new AABB(minX, minZ, maxX, maxZ);
        }

        AABB inflate(int r) {
            return new AABB(this.x1 - r, this.z1 - r, this.x2 + r, this.z2 + r);
        }

        boolean intersectsInflated(AABB other) {
            return this.x1 <= other.x2 && this.x2 >= other.x1 && this.z1 <= other.z2 && this.z2 >= other.z1;
        }
    }

    private record MergeCandidate(String otherKey, double score) {
    }

    private record Convergence(int i, int j) {
    }

    private static final class BuildResult {
        final Map<String, List<BlockPos>> legsRaw;
        final Trunk trunkRaw;

        BuildResult(Map<String, List<BlockPos>> legsRaw, Trunk trunkRaw) {
            this.legsRaw = legsRaw;
            this.trunkRaw = trunkRaw;
        }
    }

    private record Trunk(String key, List<BlockPos> path) {
    }
}
