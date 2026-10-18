package net.oxcodsnet.roadarchitect.handlers;

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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.oxcodsnet.roadarchitect.RoadArchitect;
import net.oxcodsnet.roadarchitect.storage.PathStorage;
import net.oxcodsnet.roadarchitect.storage.RoadBuilderStorage;
import net.oxcodsnet.roadarchitect.util.GooskerRoadDropCleanup;
import net.oxcodsnet.roadarchitect.util.GooskerRoadRules;
import net.oxcodsnet.roadarchitect.worldgen.RoadFeature;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;
import net.oxcodsnet.roadarchitect.worldgen.RoadFeatureConfig;

/**
 * Stability-first, post-generation road builder.
 *
 * <p>0.3.13 intentionally builds only one centerline point per server tick and
 * never performs construction from the chunk-generation callback. CHUNK_LOAD is
 * only a cheap notification that a previously queued road chunk became eligible.
 * This trades build latency for smooth ticks and Distant Horizons compatibility.</p>
 */
public final class GooskerRoadBackfill {
    private static final ConcurrentLinkedQueue<Request> QUEUE = new ConcurrentLinkedQueue<>();
    private static final Set<RequestKey> QUEUED = ConcurrentHashMap.newKeySet();

    /** Maximum unrelated/stale queue entries inspected in one tick. */
    private static final int MAX_QUEUE_POLLS_PER_TICK = 8;
    /** One centerline point is still the atomic construction budget. */
    private static final int MAX_POINTS_PER_TICK = 1;
    /**
     * One bounded centerline point may be written each server tick. The old
     * seven-tick DH quiet-window throttle was removed after isolation testing
     * proved DH's stalled distant generation was caused by Chunky/DH 3.3.2,
     * not Road Architect's post-load write cadence.
     */
    private static final int ROAD_WRITE_INTERVAL_TICKS = 1;
    private static int roadWriteTickCounter;

    private GooskerRoadBackfill() {
    }

    public static void queueSegmentsAndEnqueue(ServerLevel serverLevel, Map<String, List<BlockPos>> map) {
        RoadBuilderManager.queueSegments(serverLevel, map);
        HashSet<Long> chunks = new HashSet<>();
        for (List<BlockPos> list : map.values()) {
            if (list == null) continue;
            for (BlockPos blockPos : list) {
                chunks.add(pack(blockPos.getX() >> 4, blockPos.getZ() >> 4));
            }
        }
        for (long packed : chunks) {
            int cx = unpackX(packed);
            int cz = unpackZ(packed);
            // Do not keep retrying unloaded/far-away chunks. Their CHUNK_LOAD
            // event will enqueue them later when they actually exist as LevelChunks.
            if (isCenterLoaded(serverLevel, cx, cz)) {
                enqueue(serverLevel, cx, cz);
            }
        }
    }

    public static void onChunkLoadAndPipeline(ServerLevel serverLevel, ChunkAccess chunkAccess) {
        // Discover target structures, but the controller only queues a tiny
        // post-load scan; it does not execute a pipeline inside CHUNK_LOAD.
        RoadPipelineController.onChunkGenerated(serverLevel, chunkAccess);

        ChunkPos chunkPos = chunkAccess.getPos();
        if (chunkPos == null) return;

        // Critical DH protection: don't enqueue every chunk that DH touches.
        // Only chunks that actually have pending Road Architect segments matter.
        RoadBuilderStorage storage = RoadBuilderStorage.get(serverLevel);
        if (!storage.getSegments(chunkPos).isEmpty()) {
            enqueue(serverLevel, chunkPos.x, chunkPos.z);
        }
    }

    public static void onServerTickAndPipeline(MinecraftServer minecraftServer) {
        // This is now only the bounded post-load structure trigger queue, not a
        // periodic radius scan around every player.
        RoadPipelineController.onServerTick(minecraftServer);
        GooskerRoadDropCleanup.onServerTick(minecraftServer);
        processOne(minecraftServer);
    }

    public static void onServerStoppingAndPipeline() {
        RoadPipelineController.onServerStopping();
        GooskerRoadDropCleanup.clear();
        clear();
    }

    public static void clear() {
        QUEUE.clear();
        QUEUED.clear();
        roadWriteTickCounter = 0;
    }

    private static void enqueue(ServerLevel serverLevel, int chunkX, int chunkZ) {
        RequestKey key = new RequestKey(serverLevel, chunkX, chunkZ);
        if (QUEUED.add(key)) {
            QUEUE.offer(new Request(serverLevel, chunkX, chunkZ));
        }
    }

    private static void processOne(MinecraftServer minecraftServer) {
        roadWriteTickCounter = (roadWriteTickCounter + 1) % ROAD_WRITE_INTERVAL_TICKS;
        if (roadWriteTickCounter != 0) return;

        for (int i = 0; i < MAX_QUEUE_POLLS_PER_TICK; i++) {
            Request request = QUEUE.poll();
            if (request == null) return;
            QUEUED.remove(request.key());

            ServerLevel world = request.world();
            if (world == null || !isCenterLoaded(world, request.chunkX(), request.chunkZ())) {
                continue;
            }

            if (!isLoadedWithMargin(world, request.chunkX(), request.chunkZ())) {
                // Keep the request alive, but do not let one not-ready chunk
                // block ready work behind it. The poll budget remains bounded,
                // so rotating deferred work is cheap and never force-loads it.
                enqueue(world, request.chunkX(), request.chunkZ());
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(request.chunkX(), request.chunkZ());
            RoadBuilderStorage storage = RoadBuilderStorage.get(world);
            ArrayList<RoadBuilderStorage.SegmentEntry> entries = new ArrayList<>(storage.getSegments(chunkPos));
            if (entries.isEmpty()) {
                continue;
            }

            // Exactly one segment slice / one centerline point this tick.
            backfillOnePoint(world, storage, chunkPos, entries.get(0));

            if (!storage.getSegments(chunkPos).isEmpty()) {
                enqueue(world, request.chunkX(), request.chunkZ());
            }
            return;
        }
    }

    private static boolean isCenterLoaded(ServerLevel serverLevel, int chunkX, int chunkZ) {
        ServerChunkCache chunks = serverLevel.getChunkSource();
        return chunks != null && chunks.getChunkNow(chunkX, chunkZ) != null;
    }

    private static boolean isLoadedWithMargin(ServerLevel serverLevel, int chunkX, int chunkZ) {
        ServerChunkCache chunks = serverLevel.getChunkSource();
        if (chunks == null) return false;

        int halfWidth = Math.max(0, Math.max(1, RoadArchitect.CONFIG.roadWidth()) / 2);
        int margin = Math.max(1, (halfWidth + 18 + 15) / 16);
        margin = Math.min(margin, 8);
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dz = -margin; dz <= margin; dz++) {
                if (chunks.getChunkNow(chunkX + dx, chunkZ + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean backfillOnePoint(ServerLevel world,
                                            RoadBuilderStorage storage,
                                            ChunkPos chunkPos,
                                            RoadBuilderStorage.SegmentEntry entry) {
        try {
            String[] ids = entry.pathKey().split("\\|", 2);
            if (ids.length != 2) {
                storage.removeSegment(chunkPos, entry);
                return true;
            }

            List<BlockPos> path = PathStorage.get(world).getPath(ids[0], ids[1]);
            if (path == null || path.isEmpty()) {
                storage.removeSegment(chunkPos, entry);
                return true;
            }

            int start = Math.max(0, entry.start());
            int end = Math.min(path.size(), entry.end());
            if (end <= start) {
                storage.removeSegment(chunkPos, entry);
                return true;
            }

            int sliceEnd = Math.min(end, start + MAX_POINTS_PER_TICK);
            List<BlockPos> land = RoadFeature.collectLandPoints(world, path, start, sliceEnd);
            if (!land.isEmpty()) {
                int width = Math.max(1, RoadArchitect.CONFIG.roadWidth());
                if ((width & 1) == 0) --width;
                int halfWidth = Math.max(0, width / 2);
                RandomSource random = world.getRandom();
                try (var scope = RoadStructureProtection.beginPlacement(world)) {
                    RoadFeature.buildRoadStripe(world, path, start, sliceEnd, halfWidth, random, RoadFeatureConfig.GenerationPhase.PREPARE);
                    // PREPARE queues complete-tree removal. Keep this persisted
                    // point pending until the trunk/canopy are gone, before
                    // surfacing/decorating; never finalize against a queued tree.
                    if(net.oxcodsnet.roadarchitect.storage.TreeRemovalQueue.get(world).hasPendingAround(chunkPos,2)) return false;
                    RoadFeature.buildRoadStripe(world, path, start, sliceEnd, halfWidth, random, RoadFeatureConfig.GenerationPhase.FINALIZE);
                }
                // Decorations begin outside the scope so they see newly placed blocks.
                RoadFeature.decoratePostLoadPoint(world, path, entry.pathKey(), start, sliceEnd, halfWidth, random);
            }

            // Advance the persisted task by exactly the work completed above.
            storage.removeSegment(chunkPos, entry);
            if (sliceEnd < end) {
                storage.addSegment(chunkPos, entry.pathKey(), sliceEnd, end);
            }
            return true;
        } catch (Throwable failure) {
            System.err.println("[RoadArchitect/GooskerBackfill] Failed bounded post-load road build at "
                    + chunkPos.x + "," + chunkPos.z + ": " + failure);
            return false;
        }
    }

    private static long pack(int x, int z) {
        return (long)x << 32 ^ (long)z & 0xFFFFFFFFL;
    }

    private static int unpackX(long packed) {
        return (int)(packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int)packed;
    }

    private record RequestKey(ServerLevel world, int chunkX, int chunkZ) {
    }

    private record Request(ServerLevel world, int chunkX, int chunkZ) {
        RequestKey key() {
            return new RequestKey(world, chunkX, chunkZ);
        }
    }
}
