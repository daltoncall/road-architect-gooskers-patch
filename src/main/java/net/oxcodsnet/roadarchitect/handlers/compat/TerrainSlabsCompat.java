package net.oxcodsnet.roadarchitect.handlers.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;

/**
 * Cached road-block -> slab transition compatibility.
 *
 * <p>Countered Terrain Slabs remains completely optional. When present, its
 * own mappings are copied into the cache first so its terrain-specific slabs
 * win for blocks such as granite, gravel, dirt, ice, etc. Road Architect then
 * fills any unmapped entries from the registered slab blocks already present
 * in the game. This lets ordinary vanilla and modded slabs (wood planks,
 * decorative stone, modded building blocks, and similar blocks) use the same
 * half-step road transition logic without per-placement registry scans.</p>
 *
 * <p>The registry is scanned once, lazily, after game registries are ready.
 * Normal road generation then performs only an O(1) map lookup.</p>
 */
public final class TerrainSlabsCompat {
    private static final String SLAB_MAP_CLASS = "net.countered.terrainslabs.block.ModSlabsMap";
    private static final long RECENT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final Map<Object, ConcurrentMap<PosKey, Long>> RECENT_ROAD_CELLS = new ConcurrentHashMap<>();
    private static final AtomicInteger PLACEMENTS = new AtomicInteger();
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean COUNTERED_FAILURE_REPORTED = new AtomicBoolean();
    private static final Object SLAB_CACHE_LOCK = new Object();

    private static volatile boolean lookupChecked;
    private static volatile boolean compatibilityDisabled;
    private static volatile boolean counteredLookupDisabled;
    private static volatile Class<?> counteredMapClass;
    private static volatile Method getSlabForBlock;
    private static volatile Map<Block, Block> slabCache;

    private TerrainSlabsCompat() {
    }

    /** Called after a road surface cell finishes FINALIZE placement. */
    public static void onRoadPlaced(WorldGenLevel world, BlockPos roadPos) {
        if (compatibilityDisabled || world == null || roadPos == null) {
            return;
        }

        try {
            onRoadPlacedSafe(world, roadPos);
        } catch (Throwable failure) {
            disableAfterFailure(failure);
        }
    }

    private static void onRoadPlacedSafe(WorldGenLevel world, BlockPos roadPos) {
        // Build the mapping lazily. Countered Terrain Slabs is optional; a
        // missing Countered lookup does not disable vanilla/modded slab matching.
        ensureSlabCache();
        if (RoadStructureProtection.hasNearbyBuildingForFinalize(world, roadPos)) {
            return;
        }

        Object worldKey = world;
        ConcurrentMap<PosKey, Long> recent = RECENT_ROAD_CELLS.computeIfAbsent(worldKey, key -> new ConcurrentHashMap<>());
        long now = System.nanoTime();
        PosKey current = new PosKey(roadPos.getX(), roadPos.getY(), roadPos.getZ());
        recent.put(current, now + RECENT_WINDOW_NANOS);

        // A one-block rise becomes: full block -> bottom slab -> full block.
        // Only cells that Road Architect actually placed recently participate,
        // so ordinary terrain beside the trail is never converted into a slab.
        final int[][] cardinal = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : cardinal) {
            for (int dy : new int[]{-1, 1}) {
                PosKey neighbor = new PosKey(current.x() + offset[0], current.y() + dy, current.z() + offset[1]);
                Long expiry = recent.get(neighbor);
                if (expiry == null) {
                    continue;
                }
                if (expiry < now) {
                    recent.remove(neighbor, expiry);
                    continue;
                }
                BlockPos lower = dy > 0
                        ? roadPos
                        : new BlockPos(neighbor.x(), neighbor.y(), neighbor.z());
                placeTransition(world, lower);
            }
        }

        if ((PLACEMENTS.incrementAndGet() & 127) == 0) {
            prune(worldKey, recent, now);
        }
    }

    private static void placeTransition(WorldGenLevel world, BlockPos lowerRoadPos) {
        BlockState roadState = loadedState(world, lowerRoadPos);
        if (roadState == null) {
            return;
        }

        // Keep this strongly typed. alpha-0.2.12's hand-built runtime class
        // accidentally emitted BlockState#getBlock() with an Object return
        // descriptor, which caused a NoSuchMethodError in Minecraft 1.20.1.
        Block roadBlock = roadState.getBlock();
        Block slabBlock = slabFor(roadBlock);
        if (slabBlock == null) {
            // No registered slab pair exists for this configured road block.
            // Leave the ordinary full-block step intact.
            return;
        }

        BlockPos slabPos = lowerRoadPos.above();
        BlockState existing = loadedState(world, slabPos);
        if (existing == null || !existing.isAir()) {
            // Do not erase flowers, grass, leaf litter, snow, or other trail
            // dressing just to force a half-step into this one cell.
            return;
        }

        BlockState slabState = makeGeneratedBottomSlab(slabBlock.defaultBlockState());
        if (slabState == null) {
            return;
        }

        if (loaded(world, slabPos.getX(), slabPos.getZ())) {
            world.setBlock(slabPos, slabState, 2);
        }
    }

    /** Loaded-only access so optional slab compatibility can never request a chunk. */
    private static boolean loaded(WorldGenLevel world, int x, int z) {
        try {
            if (world instanceof ServerLevel level) {
                ServerChunkCache chunks = level.getChunkSource();
                return chunks != null && chunks.getChunkNow(x >> 4, z >> 4) != null;
            }
            return world.hasChunk(x >> 4, z >> 4);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static BlockState loadedState(WorldGenLevel world, BlockPos pos) {
        try {
            if (world instanceof ServerLevel level) {
                ServerChunkCache chunks = level.getChunkSource();
                if (chunks == null) return null;
                LevelChunk chunk = chunks.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                return chunk == null ? null : chunk.getBlockState(pos);
            }
            return loaded(world, pos.getX(), pos.getZ()) ? world.getBlockState(pos) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static BlockState makeGeneratedBottomSlab(BlockState state) {
        if (state == null) {
            return null;
        }
        if (state.hasProperty(SlabBlock.TYPE)) {
            state = state.setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        }
        if (state.hasProperty(SlabBlock.WATERLOGGED)) {
            state = state.setValue(SlabBlock.WATERLOGGED, false);
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof BooleanProperty booleanProperty
                    && "generated".equals(property.getName())) {
                state = state.setValue(booleanProperty, true);
                break;
            }
        }
        return state;
    }

    private static Block slabFor(Block block) {
        if (block == null || compatibilityDisabled) {
            return null;
        }
        return ensureSlabCache().get(block);
    }

    /**
     * Build one immutable block -> slab map for the session.
     * Countered's explicit mappings take priority; generic registry matches only
     * fill blocks that Countered did not already map.
     */
    private static Map<Block, Block> ensureSlabCache() {
        Map<Block, Block> cached = slabCache;
        if (cached != null) {
            return cached;
        }

        synchronized (SLAB_CACHE_LOCK) {
            cached = slabCache;
            if (cached != null) {
                return cached;
            }

            IdentityHashMap<Block, Block> resolved = new IdentityHashMap<>();
            HashMap<String, Block> byTranslationKey = new HashMap<>();
            List<Block> registeredBlocks = new ArrayList<>();
            List<Block> registeredSlabs = new ArrayList<>();

            for (Block block : BuiltInRegistries.BLOCK) {
                registeredBlocks.add(block);
                String key = translationKey(block);
                if (key != null) {
                    byTranslationKey.putIfAbsent(key, block);
                }
                if (block instanceof SlabBlock) {
                    registeredSlabs.add(block);
                }
            }

            // Copy Countered's own explicit map once. This is intentionally a
            // bulk copy instead of reflectively calling getSlabForBlock for every
            // registered block in a large modpack. If a future Countered version
            // hides/renames that field, fall back to its public lookup method.
            copyCounteredMappings(resolved, registeredBlocks);

            // Fill every remaining block from slabs already registered by
            // vanilla or any installed mod. Countered entries above always win.
            for (Block slab : registeredSlabs) {
                String slabKey = translationKey(slab);
                if (slabKey == null) {
                    continue;
                }
                for (String baseKey : possibleBaseTranslationKeys(slabKey)) {
                    Block base = byTranslationKey.get(baseKey);
                    if (base != null && !(base instanceof SlabBlock)) {
                        resolved.putIfAbsent(base, slab);
                        break;
                    }
                }
            }

            cached = Collections.unmodifiableMap(resolved);
            slabCache = cached;
            return cached;
        }
    }


    private static void copyCounteredMappings(IdentityHashMap<Block, Block> resolved,
                                                List<Block> registeredBlocks) {
        if (!ensureCounteredLookup()) {
            return;
        }

        try {
            Field field = counteredMapClass.getDeclaredField("SLAB_MAP");
            field.setAccessible(true);
            Object rawMap = field.get(null);
            if (rawMap instanceof Map<?, ?> map) {
                boolean copiedAny = false;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof Block base
                            && entry.getValue() instanceof SlabBlock slab) {
                        resolved.put(base, slab);
                        copiedAny = true;
                    }
                }
                if (copiedAny || map.isEmpty()) {
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Preserve compatibility with a future Countered implementation by
            // falling back to its public API below.
        }

        for (Block block : registeredBlocks) {
            try {
                Object result = getSlabForBlock.invoke(null, block);
                if (result instanceof SlabBlock slab) {
                    resolved.put(block, slab);
                }
            } catch (Throwable failure) {
                disableCounteredLookup(failure);
                return;
            }
        }
    }

    private static String translationKey(Block block) {
        if (block == null) {
            return null;
        }
        try {
            String key = block.getDescriptionId();
            return key == null || key.isBlank() ? null : key;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Infer the base block names used by vanilla and common mod conventions.
     * Examples: granite_slab -> granite, oak_slab -> oak_planks,
     * stone_brick_slab -> stone_bricks, quartz_slab -> quartz_block.
     */
    private static List<String> possibleBaseTranslationKeys(String slabKey) {
        int firstDot = slabKey.indexOf('.');
        int secondDot = firstDot < 0 ? -1 : slabKey.indexOf('.', firstDot + 1);
        if (secondDot < 0) {
            return List.of();
        }

        String prefix = slabKey.substring(0, secondDot + 1);
        String path = slabKey.substring(secondDot + 1);
        if (!path.endsWith("_slab") || path.length() <= 5) {
            return List.of();
        }

        String stem = path.substring(0, path.length() - 5);
        ArrayList<String> candidates = new ArrayList<>(6);
        addCandidate(candidates, prefix + stem);                  // granite -> granite_slab
        addCandidate(candidates, prefix + stem + "_planks");     // oak_planks -> oak_slab
        if (stem.endsWith("_brick") || stem.endsWith("_tile")) {
            addCandidate(candidates, prefix + stem + "s");       // stone_bricks / deepslate_tiles
        }
        addCandidate(candidates, prefix + stem + "_block");      // quartz_block / purpur_block
        addCandidate(candidates, prefix + stem + "s");           // brick(s) and modded plural conventions
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String candidate) {
        if (!candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private static boolean ensureCounteredLookup() {
        if (counteredLookupDisabled || compatibilityDisabled) {
            return false;
        }
        if (lookupChecked) {
            return getSlabForBlock != null;
        }
        synchronized (TerrainSlabsCompat.class) {
            if (lookupChecked) {
                return getSlabForBlock != null;
            }
            try {
                Class<?> slabMap = Class.forName(SLAB_MAP_CLASS, false, TerrainSlabsCompat.class.getClassLoader());
                counteredMapClass = slabMap;
                for (Method method : slabMap.getMethods()) {
                    if ("getSlabForBlock".equals(method.getName()) && method.getParameterCount() == 1) {
                        method.setAccessible(true);
                        getSlabForBlock = method;
                        break;
                    }
                }
            } catch (ClassNotFoundException absent) {
                // Optional mod not installed: generic vanilla/modded slab matching still works.
                counteredMapClass = null;
                getSlabForBlock = null;
            } catch (Throwable failure) {
                disableCounteredLookup(failure);
            } finally {
                lookupChecked = true;
            }
            return !counteredLookupDisabled && getSlabForBlock != null;
        }
    }

    private static void disableCounteredLookup(Throwable failure) {
        counteredLookupDisabled = true;
        counteredMapClass = null;
        getSlabForBlock = null;
        if (COUNTERED_FAILURE_REPORTED.compareAndSet(false, true)) {
            System.err.println("[Road Architect] Countered Terrain Slabs lookup disabled after an API/linkage failure; "
                    + "generic registered-slab matching will continue: "
                    + failure.getClass().getName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        }
    }

    private static void disableAfterFailure(Throwable failure) {
        compatibilityDisabled = true;
        counteredMapClass = null;
        getSlabForBlock = null;
        slabCache = Collections.emptyMap();
        RECENT_ROAD_CELLS.clear();
        if (FAILURE_REPORTED.compareAndSet(false, true)) {
            System.err.println("[Road Architect] Slab transition compatibility disabled after a runtime failure: "
                    + failure.getClass().getName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        }
    }

    private static void prune(Object worldKey, ConcurrentMap<PosKey, Long> recent, long now) {
        recent.entrySet().removeIf(entry -> entry.getValue() < now);
        if (recent.isEmpty()) {
            RECENT_ROAD_CELLS.remove(worldKey, recent);
        }
    }

    private record PosKey(int x, int y, int z) {
    }
}
