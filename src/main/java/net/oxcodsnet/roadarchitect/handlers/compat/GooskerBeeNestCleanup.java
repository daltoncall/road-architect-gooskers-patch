package net.oxcodsnet.roadarchitect.handlers.compat;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Removes natural bee nests orphaned by Road Architect tree/canopy cleanup. */
public final class GooskerBeeNestCleanup {
    private static final int TREE_RADIUS = 7;
    private static final int TREE_HEIGHT = 36;

    private GooskerBeeNestCleanup() {
    }

    public static void afterTreeCleanup(WorldGenLevel world, List<BlockPos> trunks) {
        if (world == null || trunks == null || trunks.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        for (BlockPos trunk : trunks) {
            minX = Math.min(minX, trunk.getX());
            maxX = Math.max(maxX, trunk.getX());
            minZ = Math.min(minZ, trunk.getZ());
            maxZ = Math.max(maxZ, trunk.getZ());
            minY = Math.min(minY, trunk.getY());
        }
        clearBeeNests(world,
                minX - TREE_RADIUS, maxX + TREE_RADIUS,
                minY - 4, minY + TREE_HEIGHT,
                minZ - TREE_RADIUS, maxZ + TREE_RADIUS);
    }

    public static void afterOrphanLeavesCleanup(WorldGenLevel world, BlockPos roadPos) {
        if (world == null || roadPos == null) {
            return;
        }
        clearBeeNests(world,
                roadPos.getX() - TREE_RADIUS, roadPos.getX() + TREE_RADIUS,
                roadPos.getY() - 2, roadPos.getY() + TREE_HEIGHT,
                roadPos.getZ() - TREE_RADIUS, roadPos.getZ() + TREE_RADIUS);
    }

    private static void clearBeeNests(WorldGenLevel world,
                                      int minX, int maxX,
                                      int minY, int maxY,
                                      int minZ, int maxZ) {
        minY = Math.max(world.getMinBuildHeight(), minY);
        maxY = Math.min(world.getMaxBuildHeight() - 1, maxY);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!loaded(world, x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = safeState(world, pos);
                    if (state != null && state.is(Blocks.BEE_NEST)) {
                        // BEE_NEST is the natural tree-generated block. We do
                        // not match Blocks.BEEHIVE, so crafted/player beehives
                        // are never deleted by this cleanup.
                        safeRemove(world, pos);
                    }
                }
            }
        }
    }

    private static boolean loaded(WorldGenLevel world, int x, int z) {
        try {
            return world.hasChunk(x >> 4, z >> 4);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static BlockState safeState(WorldGenLevel world, BlockPos pos) {
        try {
            return loaded(world, pos.getX(), pos.getZ()) ? world.getBlockState(pos) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void safeRemove(WorldGenLevel world, BlockPos pos) {
        try {
            if (loaded(world, pos.getX(), pos.getZ())) {
                world.removeBlock(pos, false);
            }
        } catch (Throwable ignored) {
        }
    }
}
