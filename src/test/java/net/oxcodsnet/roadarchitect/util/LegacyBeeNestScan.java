package net.oxcodsnet.roadarchitect.util;

import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Removes natural bee nests and unsupported snow orphaned by tree/canopy cleanup. */
public final class LegacyBeeNestScan {
    private static final int TREE_RADIUS = 7;
    private static final int TREE_HEIGHT = 48;

    private LegacyBeeNestScan() {}

    public static void afterTreeCleanup(WorldGenLevel world, List<BlockPos> trunks) {
        if (world == null || trunks == null || trunks.isEmpty()) return;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        for (BlockPos trunk : trunks) {
            minX = Math.min(minX, trunk.getX());
            maxX = Math.max(maxX, trunk.getX());
            minZ = Math.min(minZ, trunk.getZ());
            maxZ = Math.max(maxZ, trunk.getZ());
            minY = Math.min(minY, trunk.getY());
        }
        clearBeeNestsAndSnow(world, minX - TREE_RADIUS, maxX + TREE_RADIUS,
                minY - 4, minY + TREE_HEIGHT + 2, minZ - TREE_RADIUS, maxZ + TREE_RADIUS);
    }

    public static void afterOrphanLeavesCleanup(WorldGenLevel world, BlockPos roadPos) {
        if (world == null || roadPos == null) return;
        clearBeeNestsAndSnow(world,
                roadPos.getX() - TREE_RADIUS, roadPos.getX() + TREE_RADIUS,
                roadPos.getY() - 2, roadPos.getY() + TREE_HEIGHT + 2,
                roadPos.getZ() - TREE_RADIUS, roadPos.getZ() + TREE_RADIUS);
    }

    private static void clearBeeNestsAndSnow(WorldGenLevel world, int minX, int maxX,
                                              int minY, int maxY, int minZ, int maxZ) {
        minY = Math.max(world.getMinBuildHeight(), minY);
        maxY = Math.min(world.getMaxBuildHeight() - 1, maxY);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!loaded(world, x, z)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = safeState(world, pos);
                    if (isBeeNest(state)) {
                        // Natural bee nests are generated tree attachments.
                        // Crafted minecraft:beehive is intentionally not matched.
                        safeRemove(world, pos);
                        continue;
                    }
                    if (isSnowLayer(state)) {
                        BlockState below = safeState(world, new BlockPos(x, y - 1, z));
                        if (!supportsSnow(below)) safeRemove(world, pos);
                    }
                }
            }
        }
    }

    private static boolean isBeeNest(BlockState state) {
        return hasExactId(state, "bee_nest");
    }

    private static boolean isSnowLayer(BlockState state) {
        return hasExactId(state, "snow");
    }

    private static boolean hasExactId(BlockState state, String id) {
        if (state == null) return false;
        String text = String.valueOf(state).toLowerCase(Locale.ROOT);
        String full = "minecraft:" + id;
        return text.contains("{" + full + "}") || text.contains("{" + full + "}[")
                || text.contains("block{" + full + "}") || text.equals(full)
                || text.startsWith(full + "[");
    }

    private static boolean supportsSnow(BlockState below) {
        if (below == null) return false;
        String text = String.valueOf(below).toLowerCase(Locale.ROOT);
        return !text.contains("air") && !text.contains("minecraft:water") && !text.contains("minecraft:lava");
    }

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

    private static BlockState safeState(WorldGenLevel world, BlockPos pos) {
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

    private static void safeRemove(WorldGenLevel world, BlockPos pos) {
        try {
            if (loaded(world, pos.getX(), pos.getZ())) world.removeBlock(pos, false);
        } catch (Throwable ignored) {}
    }
}
