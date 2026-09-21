package net.oxcodsnet.roadarchitect.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.oxcodsnet.roadarchitect.handlers.compat.GooskerBeeNestCleanup;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;

/** Bounded vegetation removal for trees or bamboo that physically intersect a road. */
public final class GooskerRoadVegetation {
    private static final int TRIGGER_RADIUS = 2;
    private static final int TRIGGER_DOWN = 2;
    private static final int TRIGGER_UP = 12;
    private static final int TREE_RADIUS = 7;
    private static final int TREE_HEIGHT = 36;
    private static final int BAMBOO_RADIUS = 2;
    private static final int BAMBOO_HEIGHT = 40;

    private GooskerRoadVegetation() {
    }

    public static BlockPos keepCenterline(WorldGenLevel world, BlockPos pos) {
        return pos;
    }

    public static boolean isTreeMaterial(BlockState state) {
        int kind = kind(state);
        return kind == 1 || kind == 2;
    }

    public static void clearForRoad(WorldGenLevel world, BlockPos roadPos) {
        if (world == null || roadPos == null || !loaded(world, roadPos.getX(), roadPos.getZ())) {
            return;
        }
        if (RoadStructureProtection.hasNearbyBuildingForFinalize(world, roadPos)) {
            return;
        }

        List<BlockPos> trunks = new ArrayList<>();
        List<BlockPos> leaves = new ArrayList<>();
        List<BlockPos> bamboo = new ArrayList<>();
        for (int dx = -TRIGGER_RADIUS; dx <= TRIGGER_RADIUS; dx++) {
            for (int dz = -TRIGGER_RADIUS; dz <= TRIGGER_RADIUS; dz++) {
                int x = roadPos.getX() + dx;
                int z = roadPos.getZ() + dz;
                if (!loaded(world, x, z)) {
                    continue;
                }
                for (int dy = -TRIGGER_DOWN; dy <= TRIGGER_UP; dy++) {
                    BlockPos scan = new BlockPos(x, roadPos.getY() + dy, z);
                    int kind = kind(safeState(world, scan));
                    if (kind == 1) {
                        trunks.add(scan);
                    } else if (kind == 2) {
                        leaves.add(scan);
                    } else if (kind == 3) {
                        bamboo.add(scan);
                    }
                }
            }
        }

        if (!bamboo.isEmpty()) {
            clearBamboo(world, roadPos);
        }
        // A canopy can cross the trail while its trunk sits several blocks to
        // the side. Find that trunk before cleanup so we remove the whole tree
        // rather than cutting a hole in its leaves. If no trunk exists, the
        // intersecting leaf cluster is already orphaned and should go.
        if (trunks.isEmpty() && !leaves.isEmpty()) {
            findNearbyTrunks(world, roadPos, trunks);
        }
        if (!trunks.isEmpty()) {
            clearIntersectingTree(world, trunks);
        } else if (!leaves.isEmpty()) {
            clearOrphanLeaves(world, roadPos);
        }
    }

    private static void findNearbyTrunks(WorldGenLevel world, BlockPos roadPos, List<BlockPos> trunks) {
        int minY = Math.max(world.getMinBuildHeight(), roadPos.getY() - 4);
        int maxY = Math.min(world.getMaxBuildHeight() - 1, roadPos.getY() + TREE_HEIGHT);
        for (int dx = -TREE_RADIUS; dx <= TREE_RADIUS; dx++) {
            for (int dz = -TREE_RADIUS; dz <= TREE_RADIUS; dz++) {
                int x = roadPos.getX() + dx;
                int z = roadPos.getZ() + dz;
                if (!loaded(world, x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (kind(safeState(world, pos)) == 1) {
                        trunks.add(pos);
                    }
                }
            }
        }
    }

    private static void clearOrphanLeaves(WorldGenLevel world, BlockPos roadPos) {
        int minY = Math.max(world.getMinBuildHeight(), roadPos.getY() - 2);
        int maxY = Math.min(world.getMaxBuildHeight() - 1, roadPos.getY() + TREE_HEIGHT);
        for (int dx = -TREE_RADIUS; dx <= TREE_RADIUS; dx++) {
            for (int dz = -TREE_RADIUS; dz <= TREE_RADIUS; dz++) {
                int x = roadPos.getX() + dx;
                int z = roadPos.getZ() + dz;
                if (!loaded(world, x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (kind(safeState(world, pos)) == 2) {
                        safeRemove(world, pos);
                    }
                }
            }
        }
        GooskerBeeNestCleanup.afterOrphanLeavesCleanup(world, roadPos);
    }

    private static void clearBamboo(WorldGenLevel world, BlockPos roadPos) {
        int minY = Math.max(world.getMinBuildHeight(), roadPos.getY() - 4);
        int maxY = Math.min(world.getMaxBuildHeight() - 1, roadPos.getY() + BAMBOO_HEIGHT);
        for (int dx = -BAMBOO_RADIUS; dx <= BAMBOO_RADIUS; dx++) {
            for (int dz = -BAMBOO_RADIUS; dz <= BAMBOO_RADIUS; dz++) {
                int x = roadPos.getX() + dx;
                int z = roadPos.getZ() + dz;
                if (!loaded(world, x, z)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (kind(safeState(world, pos)) == 3) {
                        safeRemove(world, pos);
                    }
                }
            }
        }
    }

    private static void clearIntersectingTree(WorldGenLevel world, List<BlockPos> trunks) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int baseY = Integer.MAX_VALUE;
        for (BlockPos trunk : trunks) {
            minX = Math.min(minX, trunk.getX());
            maxX = Math.max(maxX, trunk.getX());
            minZ = Math.min(minZ, trunk.getZ());
            maxZ = Math.max(maxZ, trunk.getZ());
            int y = trunk.getY();
            while (y > world.getMinBuildHeight() && kind(safeState(world, new BlockPos(trunk.getX(), y - 1, trunk.getZ()))) == 1) {
                y--;
            }
            baseY = Math.min(baseY, y);
        }

        int fromX = minX - TREE_RADIUS;
        int toX = maxX + TREE_RADIUS;
        int fromZ = minZ - TREE_RADIUS;
        int toZ = maxZ + TREE_RADIUS;
        int fromY = Math.max(world.getMinBuildHeight(), baseY - 2);
        int toY = Math.min(world.getMaxBuildHeight() - 1, baseY + TREE_HEIGHT);

        // Remove the full intersecting trunk and its canopy, rather than only
        // cutting the bottom blocks and leaving a floating tree behind.
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                if (!loaded(world, x, z)) {
                    continue;
                }
                for (int y = fromY; y <= toY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    int kind = kind(safeState(world, pos));
                    if (kind == 1 || kind == 2) {
                        safeRemove(world, pos);
                    }
                }
            }
        }
        // Natural bee nests can be attached to trunks/leaves but are not tree
        // material themselves, so remove only BEE_NEST blocks in this same
        // bounded cleanup area after the intersecting tree is gone.
        GooskerBeeNestCleanup.afterTreeCleanup(world, trunks);
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

    /** 0 other, 1 trunk/wood, 2 canopy leaves, 3 bamboo. */
    private static int kind(BlockState state) {
        if (state == null) {
            return 0;
        }
        String id = String.valueOf(state).toLowerCase(Locale.ROOT);
        if (id.contains("block{minecraft:bamboo}")) {
            return 3;
        }
        if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock
                || id.contains("_leaves") || id.contains("leaves[") || id.contains("_leaf")) {
            return 2;
        }
        if (state.is(BlockTags.LOGS)
                || id.contains("_log") || id.contains("_wood") || id.contains("_stem")
                || id.contains("_hyphae") || id.contains("mushroom_stem")) {
            return 1;
        }
        return 0;
    }
}
