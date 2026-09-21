package net.oxcodsnet.roadarchitect.util;

import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.oxcodsnet.roadarchitect.config.records.CacheSettings;
import net.oxcodsnet.roadarchitect.storage.CacheStorage;
import net.oxcodsnet.roadarchitect.util.AsyncExecutor;
import net.oxcodsnet.roadarchitect.util.DebugLog;
import net.oxcodsnet.roadarchitect.util.GooskerRoadRules;
import net.oxcodsnet.roadarchitect.util.cache.ChunkHeightGenerator;
import net.oxcodsnet.roadarchitect.util.cache.ChunkHeightSnapshot;
import net.oxcodsnet.roadarchitect.util.cache.WorldCacheState;
import net.oxcodsnet.roadarchitect.util.profiler.PipelineProfiler;
import net.oxcodsnet.roadarchitect.worldgen.RoadFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CacheManager {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)("roadarchitect/" + CacheManager.class.getSimpleName()));
    private static final Map<ResourceKey<Level>, WorldCacheState> STATES = new ConcurrentHashMap<ResourceKey<Level>, WorldCacheState>();
    private static final int CHUNK_SIDE = 16;
    private static final int COLUMNS_PER_CHUNK = 256;

    private CacheManager() {
    }

    public static void onWorldLoad(ServerLevel world) {
        if (world.isClientSide()) {
            return;
        }
        CacheManager.load(world);
    }

    public static void onWorldUnload(ServerLevel world) {
        if (world.isClientSide()) {
            return;
        }
        CacheManager.save(world);
    }

    public static void onServerStopping(MinecraftServer server) {
        for (ServerLevel world : server.getAllLevels()) {
            CacheManager.save(world);
        }
    }

    private static WorldCacheState state(ServerLevel world) {
        return STATES.computeIfAbsent((ResourceKey<Level>)world.dimension(), k -> {
            CacheStorage storage = CacheStorage.open(world);
            return new WorldCacheState(world, storage, storage.settings());
        });
    }

    private static void load(ServerLevel world) {
        CacheStorage storage = CacheStorage.open(world);
        CacheSettings settings = storage.settings();
        STATES.put((ResourceKey<Level>)world.dimension(), new WorldCacheState(world, storage, settings));
        DebugLog.info(LOGGER, "Cache loaded for world {}", world.dimension().location());
        DebugLog.cache(LOGGER, "Cache budgets for {} -> runtime={}MiB snapshot={}MiB persisted={}MiB", world.dimension().location(), settings.runtimeBudgetMb(), settings.snapshotBudgetMb(), settings.persistedBudgetMb());
    }

    private static void save(ServerLevel world) {
        WorldCacheState state = STATES.remove(world.dimension());
        if (state != null) {
            state.flush();
            DebugLog.info(LOGGER, "Cache saved for world {}", world.dimension().location());
            DebugLog.cache(LOGGER, "Cache flushed for world {}", world.dimension().location());
        }
    }

    public static void prefill(ServerLevel world, int minX, int minZ, int maxX, int maxZ) {
        WorldCacheState state = CacheManager.state(world);
        CacheStorage storage = state.storage();
        CacheSettings cacheSettings = storage.settings();
        if (!cacheSettings.enablePrefill()) {
            DebugLog.info(LOGGER, "Skipping prefill for {} because it is disabled via config", world.dimension().location());
            DebugLog.cache(LOGGER, "Prefill skipped for {}: disabled", world.dimension().location());
            return;
        }
        long runtimeBudget = cacheSettings.runtimeBudgetBytes();
        long current = storage.runtimeWeightBytes();
        if ((double)current >= (double)runtimeBudget * 0.9) {
            DebugLog.info(LOGGER, "Skipping prefill for {} because runtime cache is at {} of {}", world.dimension().location(), current, runtimeBudget);
            DebugLog.cache(LOGGER, "Prefill skipped for {}: runtime usage {} of {}", world.dimension().location(), current, runtimeBudget);
            return;
        }
        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            return;
        }
        int totalChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        int limit = cacheSettings.clampedPrefillMaxChunks();
        if (limit <= 0 || totalChunks <= 0) {
            DebugLog.info(LOGGER, "Prefill skipped: no chunks selected or limit is zero", new Object[0]);
            DebugLog.cache(LOGGER, "Prefill skipped for {}: no eligible chunks (limit={})", world.dimension().location(), limit);
            return;
        }
        int target = Math.min(totalChunks, limit);
        ChunkGenerator generator = world.getChunkSource().getGenerator();
        RandomState randomState = world.getChunkSource().randomState();
        Climate.Sampler sampler = randomState.sampler();
        BiomeSource biomeSource = generator.getBiomeSource();
        int stride = Math.max(1, 4);
        int scheduled = 0;
        for (int chunkX = minChunkX; chunkX <= maxChunkX && scheduled < target; ++chunkX) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ && scheduled < target; ++scheduled, ++chunkZ) {
                ChunkPos pos = new ChunkPos(chunkX, chunkZ);
                AsyncExecutor.execute(() -> {
                    CacheManager.warmChunk(world, state, storage, pos);
                    CacheManager.prefillBiomes(world, storage, biomeSource, sampler, pos, stride);
                });
            }
        }
        DebugLog.info(LOGGER, "Scheduled {} chunk prefill tasks within [{}..{}]\u00d7[{}..{}]", scheduled, minX, maxX, minZ, maxZ);
        DebugLog.cache(LOGGER, "Prefill scheduled {} chunks for {} (limit={}, runtime={}MiB)", scheduled, world.dimension().location(), limit, cacheSettings.runtimeBudgetMb());
    }

    private static void warmChunk(ServerLevel world, WorldCacheState state, CacheStorage storage, ChunkPos pos) {
        long key = CacheManager.hash(pos.getMinBlockX(), pos.getMinBlockZ());
        CacheManager.ensureChunkSnapshot(world, state, storage, key);
    }

    private static void prefillBiomes(ServerLevel world, CacheStorage storage, BiomeSource biomeSource, Climate.Sampler sampler, ChunkPos pos, int stride) {
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        for (int localX = 0; localX < 16; localX += stride) {
            for (int localZ = 0; localZ < 16; localZ += stride) {
                int worldX = baseX + localX;
                int worldZ = baseZ + localZ;
                long key = CacheManager.hash(worldX, worldZ);
                if (storage.getBiome(key) != null) continue;
                Holder biome = biomeSource.getNoiseBiome(QuartPos.fromBlock((int)worldX), 316, QuartPos.fromBlock((int)worldZ), sampler);
                storage.putBiome(key, (Holder<Biome>)biome);
            }
        }
    }

    public static int getHeight(ServerLevel world, long key, IntSupplier loader) {
        PipelineProfiler.increment("cache.height.requests");
        WorldCacheState state = CacheManager.state(world);
        CacheStorage storage = state.storage();
        Integer cached = storage.getHeight(key);
        if (cached != null) {
            PipelineProfiler.increment("cache.height.hits");
            return cached;
        }
        Integer chunkCached = state.lookupHeight(key, 16);
        if (chunkCached != null) {
            storage.putHeightIfAbsent(key, chunkCached);
            PipelineProfiler.increment("cache.height.hits");
            return chunkCached;
        }
        ChunkHeightSnapshot snapshot = CacheManager.ensureChunkSnapshot(world, state, storage, key);
        if (snapshot != null) {
            int x = (int)(key >> 32);
            int z = (int)key;
            int localX = x & 0xF;
            int localZ = z & 0xF;
            int value = snapshot.get(localX, localZ);
            storage.putHeightIfAbsent(key, value);
            PipelineProfiler.increment("cache.height.hits");
            return value;
        }
        return storage.computeHeightIfAbsent(key, () -> {
            PipelineProfiler.increment("cache.height.loads");
            try (PipelineProfiler.Section section = PipelineProfiler.openSection("cache.height.load_time");){
                int value = loader.getAsInt();
                PipelineProfiler.recordValue("cache.height.loaded_value", value);
                int n = value;
                return n;
            }
        });
    }

    public static int getHeight(ServerLevel world, int x, int z) {
        long key = CacheManager.hash(x, z);
        OptionalInt prepared = RoadFeature.lookupPreparedSurface(x, z);
        if (prepared.isPresent()) {
            int value = prepared.getAsInt();
            WorldCacheState state = CacheManager.state(world);
            state.storage().putHeightIfAbsent(key, value);
            return value;
        }
        return CacheManager.getHeight(world, key, () -> {
            ChunkGenerator gen = world.getChunkSource().getGenerator();
            RandomState cfg = world.getChunkSource().randomState();
            return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, (LevelHeightAccessor)world, cfg);
        });
    }

    public static double getStability(ServerLevel world, long key, DoubleSupplier loader) {
        PipelineProfiler.increment("cache.stability.requests");
        WorldCacheState state = CacheManager.state(world);
        CacheStorage storage = state.storage();
        Double cached = storage.getStability(key);
        if (cached != null) {
            PipelineProfiler.increment("cache.stability.hits");
            return cached;
        }
        return storage.computeStabilityIfAbsent(key, () -> {
            PipelineProfiler.increment("cache.stability.loads");
            try (PipelineProfiler.Section section = PipelineProfiler.openSection("cache.stability.load_time");){
                double value = loader.getAsDouble();
                PipelineProfiler.recordValue("cache.stability.loaded_value", value);
                double d = value;
                return d;
            }
        });
    }

    public static Holder<Biome> getBiome(ServerLevel world, long key, Supplier<Holder<Biome>> loader) {
        PipelineProfiler.increment("cache.biome.requests");
        WorldCacheState state = CacheManager.state(world);
        CacheStorage storage = state.storage();
        Holder<Biome> cached = storage.getBiome(key);
        if (cached != null) {
            PipelineProfiler.increment("cache.biome.hits");
            return cached;
        }
        return storage.computeBiomeIfAbsent(key, () -> {
            PipelineProfiler.increment("cache.biome.loads");
            try (PipelineProfiler.Section section = PipelineProfiler.openSection("cache.biome.load_time");){
                Holder value;
                Holder holder = value = (Holder)loader.get();
                return holder;
            }
        });
    }

    public static long hash(int x, int z) {
        return (long)x << 32 | (long)z & 0xFFFFFFFFL;
    }

    public static BlockPos keyToPos(long k) {
        return new BlockPos((int)(k >> 32), 0, (int)k);
    }

    public static void onChunkLoad(ServerLevel world, ChunkAccess chunk) {
        if (world.isClientSide()) {
            return;
        }
        WorldCacheState state = STATES.get(world.dimension());
        if (state == null) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        int startX = pos.getMinBlockX();
        int startZ = pos.getMinBlockZ();
        int minY = state.minWorldY();
        Heightmap wg = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);
        if (wg == null && surface == null) {
            return;
        }
        if (wg == null) {
            wg = surface;
        }
        if (surface == null) {
            surface = wg;
        }
        int[] snapshot = new int[256];
        for (int localZ = 0; localZ < 16; ++localZ) {
            for (int localX = 0; localX < 16; ++localX) {
                int idx = localZ * 16 + localX;
                int height = wg.getFirstAvailable(localX, localZ);
                if (height <= minY) {
                    height = surface.getFirstAvailable(localX, localZ);
                }
                snapshot[idx] = height = GooskerRoadRules.adjustLoadedHeight(world, chunk, localX, localZ, height);
            }
        }
        long chunkKey = pos.toLong();
        ResourceKey dimensionKey = world.dimension();
        AsyncExecutor.execute(() -> {
            CacheStorage storage = state.storage();
            for (int localZ = 0; localZ < 16; ++localZ) {
                for (int localX = 0; localX < 16; ++localX) {
                    int idx = localZ * 16 + localX;
                    long key = CacheManager.hash(startX + localX, startZ + localZ);
                    storage.putHeight(key, snapshot[idx]);
                }
            }
            state.putChunkSnapshot(chunkKey, new ChunkHeightSnapshot(snapshot, 16));
            DebugLog.cache(LOGGER, "Chunk snapshot refreshed for {} ({})", pos, dimensionKey.location());
        });
    }

    public static void onChunkUnload(ServerLevel world, ChunkPos pos) {
        if (world.isClientSide()) {
            return;
        }
        WorldCacheState state = STATES.get(world.dimension());
        if (state != null) {
            state.removeChunkSnapshot(pos.toLong());
            DebugLog.cache(LOGGER, "Chunk snapshot released for {} ({})", pos, world.dimension().location());
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private static ChunkHeightSnapshot ensureChunkSnapshot(ServerLevel world, WorldCacheState state, CacheStorage storage, long key) {
        int x = (int)(key >> 32);
        int z = (int)key;
        ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);
        long chunkKey = chunkPos.toLong();
        ChunkHeightSnapshot cached = state.getChunkSnapshot(chunkKey);
        if (cached != null) {
            return cached;
        }
        CompletableFuture<ChunkHeightSnapshot> future = new CompletableFuture<ChunkHeightSnapshot>();
        CompletableFuture existing = state.chunkComputations().putIfAbsent(chunkKey, future);
        if (existing != null) {
            try {
                return (ChunkHeightSnapshot)existing.join();
            }
            catch (RuntimeException e) {
                DebugLog.info(LOGGER, "Height snapshot future failed for chunk {}", chunkPos, e);
                return null;
            }
        }
        try {
            ChunkHeightSnapshot generated = ChunkHeightGenerator.generate(world, state, storage, chunkPos, 16, 256);
            if (generated != null) {
                state.putChunkSnapshot(chunkKey, generated);
                DebugLog.cache(LOGGER, "Snapshot computed for chunk {} ({} columns)", chunkPos, generated.columns());
            }
            future.complete(generated);
            ChunkHeightSnapshot chunkHeightSnapshot = generated;
            return chunkHeightSnapshot;
        }
        catch (RuntimeException e) {
            future.completeExceptionally(e);
            LOGGER.error("Failed to compute height snapshot for chunk {}", (Object)chunkPos, (Object)e);
            ChunkHeightSnapshot chunkHeightSnapshot = null;
            return chunkHeightSnapshot;
        }
        finally {
            state.chunkComputations().remove(chunkKey);
        }
    }

    public static CacheStats stats(ServerLevel world) {
        WorldCacheState state = STATES.get(world.dimension());
        if (state == null) {
            return CacheStats.UNAVAILABLE;
        }
        CacheStorage storage = state.storage();
        CacheSettings settings = storage.settings();
        return new CacheStats(true, storage.runtimeWeightBytes(), settings.runtimeBudgetBytes(), state.snapshotWeightBytes(), settings.snapshotBudgetBytes(), storage.regionWeightBytes(), settings.persistedBudgetBytes(), settings.enablePrefill(), settings.clampedPrefillMaxChunks());
    }

    public record CacheStats(boolean available, long runtimeUsedBytes, long runtimeBudgetBytes, long snapshotUsedBytes, long snapshotBudgetBytes, long persistedUsedBytes, long persistedBudgetBytes, boolean prefillEnabled, int prefillMaxChunks) {
        public static final CacheStats UNAVAILABLE = new CacheStats(false, 0L, 0L, 0L, 0L, 0L, 0L, false, 0);
    }
}

