package net.oxcodsnet.roadarchitect.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;

/**
 * Bounded cleanup for natural tree/bamboo material that intersects a road.
 *
 * <p>Natural-tree detection intentionally follows the same core signal used by
 * Tree Harvester: a healthy tree has connected logs and non-persistent leaves.
 * Older canopy-stripped trunks also qualify when rooted, vertical and isolated from buildings. Player-placed vanilla leaves are persistent, so timber builds are not
 * treated as trees merely because they contain logs. Player placement history and generated structure bounds protect builds.</p>
 *
 * <p>PREPARE and FINALIZE both call this helper. All live-world reads use
 * getChunkNow(), so the detector never force-loads or waits for a chunk.</p>
 */
public final class GooskerRoadVegetation {
    private static final int TRIGGER_RADIUS = 2;
    private static final int TRIGGER_DOWN = 2;
    private static final int TRIGGER_UP = 16;

    // Keep destructive tree cleanup at the established seven-block width.
    private static final int TREE_RADIUS = 7;
    private static final int TREE_HEIGHT = 128;

    private static final int BAMBOO_RADIUS = 2;
    private static final int BAMBOO_HEIGHT = 40;

    private static final int OTHER = 0;
    private static final int TRUNK = 1;
    private static final int LEAVES = 2;
    private static final int BAMBOO = 3;
    private static final int TREE_ATTACHMENT = 4;

    private static final Map<BlockState, Integer> KINDS = new ConcurrentHashMap<>();

    public static void clearClassificationCache() { KINDS.clear(); }

    private GooskerRoadVegetation() {
    }

    public static BlockPos keepCenterline(WorldGenLevel world, BlockPos pos) {
        return pos;
    }

    /** Tree material used by drop cleanup; attachments are handled separately. */
    public static boolean isTreeMaterial(BlockState state) {
        int kind = kind(state);
        return kind == TRUNK || kind == LEAVES;
    }

    public static void clearForRoad(WorldGenLevel world, BlockPos roadPos) {
        clearForRoad(world,roadPos,TRIGGER_RADIUS);
    }

    /** Completed routes are revisited across their width, including damaged edge trunks. */
    public static void clearForSavedRoad(WorldGenLevel world,BlockPos roadPos,int halfWidth) {
        clearForRoad(world,roadPos,Math.min(TREE_RADIUS,Math.max(TRIGGER_RADIUS,halfWidth+TRIGGER_RADIUS)));
    }

    private static void clearForRoad(WorldGenLevel world,BlockPos roadPos,int triggerRadius) {
        if (world == null || roadPos == null || !loaded(world, roadPos.getX(), roadPos.getZ())) {
            return;
        }
        if (!RoadStructureProtection.firstVegetationPass(world, roadPos)) return;
        if (RoadStructureProtection.hasNearbyBuildingForFinalize(world, roadPos)) {
            return;
        }

        List<BlockPos> trunks = new ArrayList<>();
        List<BlockPos> leaves = new ArrayList<>();
        List<BlockPos> bamboo = new ArrayList<>();
        List<BlockPos> attachments = new ArrayList<>();

        for (int dx = -triggerRadius; dx <= triggerRadius; dx++) {
            for (int dz = -triggerRadius; dz <= triggerRadius; dz++) {
                int x = roadPos.getX() + dx;
                int z = roadPos.getZ() + dz;
                if (!loaded(world, x, z)) continue;
                for (int dy = -TRIGGER_DOWN; dy <= TRIGGER_UP; dy++) {
                    BlockPos scan = new BlockPos(x, roadPos.getY() + dy, z);
                    if(world instanceof ServerLevel level && net.oxcodsnet.roadarchitect.storage.TreeRemovalQueue.get(level).contains(scan)) return;
                    int kind = kind(safeState(world, scan));
                    if (kind == TRUNK) trunks.add(scan);
                    else if (kind == LEAVES) leaves.add(scan);
                    else if (kind == BAMBOO) bamboo.add(scan);
                    else if (kind == TREE_ATTACHMENT) attachments.add(scan);
                }
            }
        }

        if (!bamboo.isEmpty()) {
            clearBamboo(world, roadPos);
        }

        // A canopy can cross a trail with its trunk several blocks to the side.
        if (trunks.isEmpty() && (!leaves.isEmpty() || !attachments.isEmpty())) {
            findNearbyTrunks(world, roadPos, trunks);
        }

        if (!trunks.isEmpty()) {
            clearIntersectingTrees(world, roadPos, trunks);
        }

        // The planner collects and identifies the entire tree before any deletion.
    }

    private static void findNearbyTrunks(WorldGenLevel world, BlockPos roadPos, List<BlockPos> trunks) {
        int minY = Math.max(world.getMinBuildHeight(), roadPos.getY() - 4);
        int maxY = Math.min(world.getMaxBuildHeight() - 1, roadPos.getY() + TREE_HEIGHT);
        for (int dx = -TREE_RADIUS; dx <= TREE_RADIUS; dx++) {
            for (int dz = -TREE_RADIUS; dz <= TREE_RADIUS; dz++) {
                int x = roadPos.getX() + dx;
                int z = roadPos.getZ() + dz;
                if (!loaded(world, x, z)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (kind(safeState(world, pos)) == TRUNK) trunks.add(pos);
                }
            }
        }
    }

    /** Plan complete trees nearest first; proof includes damaged trunks automatically. */
    private static void clearIntersectingTrees(WorldGenLevel world, BlockPos roadPos, List<BlockPos> trunks) {
        Set<BlockPos> bases = new HashSet<>();
        for(BlockPos trunk:trunks) bases.add(findBase(world,trunk));
        var protectedPos=TreeBuildProtection.snapshot(world,roadPos);
        for(BlockPos base:bases.stream().sorted(java.util.Comparator.comparingDouble(pos -> pos.distSqr(roadPos))).toList()) {
            if(CompleteTreeRemoval.remove(world,base,protectedPos)>0) return;
        }
    }

    private static BlockPos findBase(WorldGenLevel world, BlockPos trunk) {
        int y = trunk.getY();
        while (y > world.getMinBuildHeight()) {
            BlockPos below = new BlockPos(trunk.getX(), y - 1, trunk.getZ());
            if (!isNaturalLog(safeState(world, below))) break;
            y--;
        }
        return new BlockPos(trunk.getX(), y, trunk.getZ());
    }

    private static boolean isNaturalLog(BlockState state) {
        if (state == null || kind(state) != TRUNK) return false;
        String id = String.valueOf(state).toLowerCase(Locale.ROOT);
        return !id.contains("stripped_");
    }

    private static void clearBamboo(WorldGenLevel world, BlockPos roadPos) {
        var protectedPos=TreeBuildProtection.snapshot(world,roadPos);
        int minY = Math.max(world.getMinBuildHeight(), roadPos.getY() - 4);
        int maxY = Math.min(world.getMaxBuildHeight() - 1, roadPos.getY() + BAMBOO_HEIGHT);
        for (int dx = -BAMBOO_RADIUS; dx <= BAMBOO_RADIUS; dx++) {
            for (int dz = -BAMBOO_RADIUS; dz <= BAMBOO_RADIUS; dz++) {
                int x = roadPos.getX() + dx;
                int z = roadPos.getZ() + dz;
                if (!loaded(world, x, z)) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (kind(safeState(world, pos)) == BAMBOO && !protectedPos.test(pos)) safeRemove(world, pos);
                }
            }
        }
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
        } catch (Throwable ignored) {
        }
    }

    /** 0 other, 1 trunk/wood, 2 canopy leaves, 3 bamboo, 4 tree attachment. */
    private static int kind(BlockState state) {
        if (state == null || state.isAir()) return OTHER;
        return KINDS.computeIfAbsent(state, GooskerRoadVegetation::classify);
    }

    private static int classify(BlockState state) {
        String id = String.valueOf(state).toLowerCase(Locale.ROOT);
        if (id.contains("block{minecraft:bamboo}")) return BAMBOO;
        if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock
                || id.contains("_leaves") || id.contains("leaves[") || id.contains("_leaf")) return LEAVES;
        if (state.is(BlockTags.LOGS)
                || id.contains("_log") || id.contains("_wood") || id.contains("_stem")
                || id.contains("_hyphae") || id.contains("mushroom_stem")
                || id.contains("mangrove_roots")) return TRUNK;
        if (id.contains("vine") || id.contains("cocoa") || id.contains("propagule")) return TREE_ATTACHMENT;
        return OTHER;
    }
}
