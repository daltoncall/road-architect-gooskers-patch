package net.oxcodsnet.roadarchitect.handlers;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.oxcodsnet.roadarchitect.RoadArchitect;
import net.oxcodsnet.roadarchitect.handlers.RoadBuilderManager;
import net.oxcodsnet.roadarchitect.handlers.RoadPipelineController;
import net.oxcodsnet.roadarchitect.storage.PathStorage;
import net.oxcodsnet.roadarchitect.storage.RoadBuilderStorage;
import net.oxcodsnet.roadarchitect.util.GooskerRoadRules;
import net.oxcodsnet.roadarchitect.util.GooskerRoadDropCleanup;
import net.oxcodsnet.roadarchitect.worldgen.RoadFeature;
import net.oxcodsnet.roadarchitect.worldgen.RoadFeatureConfig;

public final class GooskerRoadBackfill {
    private static final ConcurrentLinkedQueue<Request> QUEUE = new ConcurrentLinkedQueue();
    private static final Set<RequestKey> QUEUED = ConcurrentHashMap.newKeySet();
    private static final int MAX_SEGMENTS_PER_TICK = 2;
    private static final Method COLLECT_LAND;
    private static final Method BUILD_STRIPE;

    private GooskerRoadBackfill() {
    }

    public static void queueSegmentsAndEnqueue(ServerLevel serverLevel, Map<String, List<BlockPos>> map) {
        RoadBuilderManager.queueSegments(serverLevel, map);
        HashSet<Long> hashSet = new HashSet<Long>();
        for (List<BlockPos> list : map.values()) {
            if (list == null) continue;
            for (BlockPos blockPos : list) {
                int n = blockPos.getX() >> 4;
                int n2 = blockPos.getZ() >> 4;
                hashSet.add(GooskerRoadBackfill.pack(n, n2));
            }
        }
        Iterator<Long> iterator = hashSet.iterator();
        while (iterator.hasNext()) {
            long l = iterator.next();
            GooskerRoadBackfill.enqueue(serverLevel, GooskerRoadBackfill.unpackX(l), GooskerRoadBackfill.unpackZ(l));
        }
    }

    public static void onChunkLoadAndPipeline(ServerLevel serverLevel, ChunkAccess chunkAccess) {
        RoadPipelineController.onChunkGenerated(serverLevel, chunkAccess);
        ChunkPos chunkPos = chunkAccess.getPos();
        if (chunkPos != null) {
            GooskerRoadBackfill.enqueue(serverLevel, chunkPos.x, chunkPos.z);
        }
    }

    public static void onServerTickAndPipeline(MinecraftServer minecraftServer) {
        RoadPipelineController.onServerTick(minecraftServer);
        GooskerRoadDropCleanup.onServerTick(minecraftServer);
        GooskerRoadBackfill.processOne(minecraftServer);
    }

    public static void onServerStoppingAndPipeline() {
        RoadPipelineController.onServerStopping();
        GooskerRoadDropCleanup.clear();
        GooskerRoadBackfill.clear();
    }

    public static void clear() {
        QUEUE.clear();
        QUEUED.clear();
    }

    private static void enqueue(ServerLevel serverLevel, int n, int n2) {
        RequestKey requestKey = new RequestKey(serverLevel, n, n2);
        if (QUEUED.add(requestKey)) {
            QUEUE.offer(new Request(serverLevel, n, n2));
        }
    }

    private static void processOne(MinecraftServer minecraftServer) {
        for (int i = 0; i < 64; ++i) {
            Request request = QUEUE.poll();
            if (request == null) {
                return;
            }
            QUEUED.remove(request.key());
            ServerLevel serverLevel = request.world();
            if (serverLevel == null || !GooskerRoadBackfill.isCenterLoaded(serverLevel, request.chunkX(), request.chunkZ())) continue;
            if (!GooskerRoadBackfill.isLoadedWithMargin(serverLevel, request.chunkX(), request.chunkZ())) {
                GooskerRoadBackfill.enqueue(serverLevel, request.chunkX(), request.chunkZ());
                continue;
            }
            ChunkPos chunkPos = new ChunkPos(request.chunkX(), request.chunkZ());
            RoadBuilderStorage roadBuilderStorage = RoadBuilderStorage.get(serverLevel);
            ArrayList<RoadBuilderStorage.SegmentEntry> arrayList = new ArrayList<RoadBuilderStorage.SegmentEntry>(roadBuilderStorage.getSegments(chunkPos));
            if (arrayList.isEmpty()) {
                return;
            }
            int n = 0;
            for (RoadBuilderStorage.SegmentEntry segmentEntry : arrayList) {
                if (n >= 2) break;
                if (!GooskerRoadBackfill.backfillEntry(serverLevel, roadBuilderStorage, chunkPos, segmentEntry)) continue;
                ++n;
            }
            if (!roadBuilderStorage.getSegments(chunkPos).isEmpty()) {
                GooskerRoadBackfill.enqueue(serverLevel, request.chunkX(), request.chunkZ());
            }
            return;
        }
    }

    private static boolean isCenterLoaded(ServerLevel serverLevel, int n, int n2) {
        ServerChunkCache serverChunkCache = serverLevel.getChunkSource();
        return serverChunkCache != null && serverChunkCache.hasChunk(n, n2);
    }

    private static boolean isLoadedWithMargin(ServerLevel serverLevel, int n, int n2) {
        ServerChunkCache serverChunkCache = serverLevel.getChunkSource();
        if (serverChunkCache == null) {
            return false;
        }
        return serverChunkCache.hasChunk(n, n2) && serverChunkCache.hasChunk(n - 1, n2) && serverChunkCache.hasChunk(n + 1, n2) && serverChunkCache.hasChunk(n, n2 - 1) && serverChunkCache.hasChunk(n, n2 + 1);
    }

    private static boolean backfillEntry(ServerLevel serverLevel, RoadBuilderStorage roadBuilderStorage, ChunkPos chunkPos, RoadBuilderStorage.SegmentEntry segmentEntry) {
        try {
            String[] stringArray = segmentEntry.pathKey().split("\\|", 2);
            if (stringArray.length != 2) {
                roadBuilderStorage.removeSegment(chunkPos, segmentEntry);
                return true;
            }
            List<BlockPos> list = PathStorage.get(serverLevel).getPath(stringArray[0], stringArray[1]);
            if (list == null || list.isEmpty()) {
                roadBuilderStorage.removeSegment(chunkPos, segmentEntry);
                return true;
            }
            int n = Math.max(0, segmentEntry.start());
            int n2 = Math.min(list.size(), segmentEntry.end());
            if (n2 <= n) {
                roadBuilderStorage.removeSegment(chunkPos, segmentEntry);
                return true;
            }
            List list2 = (List)COLLECT_LAND.invoke(null, serverLevel, list, n, n2);
            if (!list2.isEmpty()) {
                int n3 = Math.max(1, RoadArchitect.CONFIG.roadWidth());
                if ((n3 & 1) == 0) {
                    --n3;
                }
                int n4 = Math.max(0, n3 / 2);
                RandomSource randomSource = serverLevel.getRandom();
                BUILD_STRIPE.invoke(null, new Object[]{serverLevel, list2, n4, randomSource, RoadFeatureConfig.GenerationPhase.PREPARE});
                BUILD_STRIPE.invoke(null, new Object[]{serverLevel, list2, n4, randomSource, RoadFeatureConfig.GenerationPhase.FINALIZE});
            }
            roadBuilderStorage.removeSegment(chunkPos, segmentEntry);
            return true;
        }
        catch (Throwable throwable) {
            System.err.println("[RoadArchitect/GooskerBackfill] Failed loaded-chunk road backfill at " + chunkPos.x + "," + chunkPos.z + ": " + String.valueOf(throwable));
            return false;
        }
    }

    private static long pack(int n, int n2) {
        return (long)n << 32 ^ (long)n2 & 0xFFFFFFFFL;
    }

    private static int unpackX(long l) {
        return (int)(l >> 32);
    }

    private static int unpackZ(long l) {
        return (int)l;
    }

    static {
        try {
            COLLECT_LAND = RoadFeature.class.getDeclaredMethod("collectLandPoints", WorldGenLevel.class, List.class, Integer.TYPE, Integer.TYPE);
            COLLECT_LAND.setAccessible(true);
            BUILD_STRIPE = GooskerRoadRules.class.getDeclaredMethod("buildRoadStripeReconciled", WorldGenLevel.class, List.class, Integer.TYPE, RandomSource.class, RoadFeatureConfig.GenerationPhase.class);
            BUILD_STRIPE.setAccessible(true);
        }
        catch (ReflectiveOperationException reflectiveOperationException) {
            throw new ExceptionInInitializerError(reflectiveOperationException);
        }
    }

    private record RequestKey(ServerLevel world, int chunkX, int chunkZ) {
    }

    private record Request(ServerLevel world, int chunkX, int chunkZ) {
        RequestKey key() {
            return new RequestKey(this.world, this.chunkX, this.chunkZ);
        }
    }
}
