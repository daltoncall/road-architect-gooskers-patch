package net.oxcodsnet.roadarchitect.handlers.compat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;

/**
 * Optional integration with Countered's Terrain Slabs.
 *
 * <p>There is intentionally no compile-time Terrain Slabs dependency. The
 * other mod's own block-to-slab map is discovered reflectively only if the mod
 * is present. Without Terrain Slabs installed this class immediately becomes a
 * no-op, so the Road Architect patch has no additional hard requirement.</p>
 *
 * <p>This entire optional bridge is fail-safe. If Terrain Slabs changes its API
 * or a linkage problem is encountered, compatibility is disabled for the rest
 * of the game session and normal full-block Road Architect generation
 * continues instead of allowing an optional integration to crash worldgen.</p>
 */
public final class TerrainSlabsCompat {
    private static final String SLAB_MAP_CLASS = "net.countered.terrainslabs.block.ModSlabsMap";
    private static final long RECENT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final Map<Object, ConcurrentMap<PosKey, Long>> RECENT_ROAD_CELLS = new ConcurrentHashMap<>();
    private static final AtomicInteger PLACEMENTS = new AtomicInteger();
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static volatile boolean lookupChecked;
    private static volatile boolean compatibilityDisabled;
    private static volatile Method getSlabForBlock;

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
        if (!ensureLookup()) {
            return;
        }
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
        if (!world.hasChunk(lowerRoadPos.getX() >> 4, lowerRoadPos.getZ() >> 4)) {
            return;
        }

        BlockState roadState = world.getBlockState(lowerRoadPos);
        if (roadState == null) {
            return;
        }

        // Keep this strongly typed. alpha-0.2.12's hand-built runtime class
        // accidentally emitted BlockState#getBlock() with an Object return
        // descriptor, which caused a NoSuchMethodError in Minecraft 1.20.1.
        Block roadBlock = roadState.getBlock();
        Block slabBlock = slabFor(roadBlock);
        if (slabBlock == null) {
            // Terrain Slabs does not provide a matching slab for this particular
            // configured road block. Leave the ordinary full-block step intact.
            return;
        }

        BlockPos slabPos = lowerRoadPos.above();
        BlockState existing = world.getBlockState(slabPos);
        if (existing == null || !existing.isAir()) {
            // Do not erase flowers, grass, leaf litter, snow, or other trail
            // dressing just to force a half-step into this one cell.
            return;
        }

        BlockState slabState = makeGeneratedBottomSlab(slabBlock.defaultBlockState());
        if (slabState == null) {
            return;
        }

        world.setBlock(slabPos, slabState, 2);
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
        if (block == null || !ensureLookup()) {
            return null;
        }
        try {
            Object result = getSlabForBlock.invoke(null, block);
            return result instanceof Block slab ? slab : null;
        } catch (Throwable failure) {
            disableAfterFailure(failure);
            return null;
        }
    }

    private static boolean ensureLookup() {
        if (compatibilityDisabled) {
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
                for (Method method : slabMap.getMethods()) {
                    if ("getSlabForBlock".equals(method.getName()) && method.getParameterCount() == 1) {
                        method.setAccessible(true);
                        getSlabForBlock = method;
                        break;
                    }
                }
            } catch (ClassNotFoundException absent) {
                // Optional mod not installed: normal no-op behavior.
                getSlabForBlock = null;
            } catch (Throwable failure) {
                disableAfterFailure(failure);
            } finally {
                lookupChecked = true;
            }
            return !compatibilityDisabled && getSlabForBlock != null;
        }
    }

    private static void disableAfterFailure(Throwable failure) {
        compatibilityDisabled = true;
        getSlabForBlock = null;
        RECENT_ROAD_CELLS.clear();
        if (FAILURE_REPORTED.compareAndSet(false, true)) {
            System.err.println("[Road Architect] Terrain Slabs compatibility disabled after an API/linkage failure: "
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
