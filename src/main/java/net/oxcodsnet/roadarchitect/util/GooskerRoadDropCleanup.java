package net.oxcodsnet.roadarchitect.util;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Suppresses delayed vegetation loot produced by freshly constructed roads.
 *
 * <p>Placed flowers, grass and leaf litter are never changed. This only
 * discards loose plant/tree item entities very close to road cells for a short
 * window after construction, catching support-pop and leaf-decay drops.</p>
 */
public final class GooskerRoadDropCleanup {
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(45L);
    private static final int HORIZONTAL_RADIUS = 3;
    private static final int BELOW = 2;
    private static final int ABOVE = 10;
    private static final Map<ServerLevel, ConcurrentMap<Long, ChunkWindow>> ACTIVE = new ConcurrentHashMap<>();

    private GooskerRoadDropCleanup() {
    }

    public static void mark(WorldGenLevel world, BlockPos roadPos) {
        if (world == null || roadPos == null) {
            return;
        }
        ServerLevel level;
        try {
            level = world.getLevel();
        } catch (Throwable ignored) {
            return;
        }
        if (level == null) {
            return;
        }
        long chunkKey = ChunkPos.asLong(roadPos.getX() >> 4, roadPos.getZ() >> 4);
        ChunkWindow window = ACTIVE.computeIfAbsent(level, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey, key -> new ChunkWindow());
        window.positions.put(roadPos.asLong(), System.nanoTime() + WINDOW_NANOS);
    }

    public static void onServerTick(MinecraftServer server) {
        if (server == null || server.getTickCount() % 5 != 0) {
            return;
        }
        long now = System.nanoTime();
        for (Map.Entry<ServerLevel, ConcurrentMap<Long, ChunkWindow>> worldEntry : ACTIVE.entrySet()) {
            ServerLevel level = worldEntry.getKey();
            ConcurrentMap<Long, ChunkWindow> chunks = worldEntry.getValue();
            for (Map.Entry<Long, ChunkWindow> chunkEntry : chunks.entrySet()) {
                long chunkKey = chunkEntry.getKey();
                ChunkWindow window = chunkEntry.getValue();
                window.positions.entrySet().removeIf(entry -> entry.getValue() < now);
                if (window.positions.isEmpty()) {
                    chunks.remove(chunkKey, window);
                    continue;
                }
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }
                AABB bounds = new AABB(
                        (chunkX << 4) - HORIZONTAL_RADIUS,
                        level.getMinBuildHeight(),
                        (chunkZ << 4) - HORIZONTAL_RADIUS,
                        (chunkX << 4) + 16 + HORIZONTAL_RADIUS,
                        level.getMaxBuildHeight(),
                        (chunkZ << 4) + 16 + HORIZONTAL_RADIUS);
                for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
                    if (isVegetationDrop(entity.getItem()) && nearMarkedRoad(entity, window, now)) {
                        entity.discard();
                    }
                }
            }
            if (chunks.isEmpty()) {
                ACTIVE.remove(level, chunks);
            }
        }
    }

    public static void clear() {
        ACTIVE.clear();
    }

    private static boolean nearMarkedRoad(ItemEntity entity, ChunkWindow window, long now) {
        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        for (Map.Entry<Long, Long> entry : window.positions.entrySet()) {
            if (entry.getValue() < now) {
                continue;
            }
            BlockPos road = BlockPos.of(entry.getKey());
            if (Math.abs(x - (road.getX() + 0.5D)) <= HORIZONTAL_RADIUS
                    && y >= road.getY() - BELOW
                    && y <= road.getY() + ABOVE
                    && Math.abs(z - (road.getZ() + 0.5D)) <= HORIZONTAL_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVegetationDrop(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            BlockState state = blockItem.getBlock().defaultBlockState();
            if (blockItem.getBlock() instanceof BushBlock || GooskerRoadVegetation.isTreeMaterial(state)) {
                return true;
            }
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        return id.contains("flower")
                || id.contains("blossom")
                || id.contains("sapling")
                || id.contains("propagule")
                || id.contains("leaves")
                || id.contains("leaf")
                || id.contains("petal")
                || id.contains("grass")
                || id.contains("fern")
                || id.contains("bush")
                || id.contains("shrub")
                || id.contains("seed")
                || id.contains("stick")
                || id.contains("apple")
                || id.contains("fruit")
                || id.contains("berry")
                || id.contains("acorn")
                || id.contains("cone")
                || id.contains("bamboo")
                || id.contains("vine")
                || id.contains("moss")
                || id.contains("lichen")
                || id.contains("root")
                || id.contains("litter");
    }

    private static final class ChunkWindow {
        final ConcurrentMap<Long, Long> positions = new ConcurrentHashMap<>();
    }
}
