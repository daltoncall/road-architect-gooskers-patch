package net.oxcodsnet.roadarchitect.worldgen;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.oxcodsnet.roadarchitect.util.GooskerRoadSurfaceRules;

/** Conservative, block-based protection for generated structures. */
public final class RoadStructureProtection {
    private static final ConcurrentHashMap<BlockState, Boolean> TERRAIN_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockState, Boolean> BUILDING_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockState, Boolean> DISTINCTIVE_CACHE = new ConcurrentHashMap<>();
    private static final int BUILDING_BUFFER = 2;
    private static final int BUILDING_SCAN_DOWN = 8;
    private static final int BUILDING_SCAN_UP = 5;

    private RoadStructureProtection() {
    }

    /** True only when a road/decor cell is loaded and is not water, ice, or a building. */
    public static boolean isNotWaterBlock(WorldGenLevel world, BlockPos pos) {
        if (world == null || pos == null || !isLoaded(world, pos)) {
            return false;
        }
        if (GooskerRoadSurfaceRules.isIceAtOrBelow(world, pos)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        String id = lower(state);
        if (isFluidBlock(state, id) || hasBuildingUnderfoot(world, pos)) {
            return false;
        }
        if (isDistinctiveBuildingBlock(state)) {
            return false;
        }
        if (isStrongBuildingBlock(state) && !isRoadSurfaceLike(id)) {
            return false;
        }
        return !hasNearbyBuilding(world, pos, BUILDING_BUFFER);
    }

    /** True when final placement must leave the current world block untouched. */
    public static boolean hasNearbyBuildingForFinalize(WorldGenLevel world, BlockPos pos) {
        return hasBuildingUnderfoot(world, pos) || hasNearbyBuilding(world, pos, BUILDING_BUFFER);
    }

    /**
     * Finds an exposed natural/previous-road surface that can safely be replaced.
     * Requiring support immediately below prevents floating road bridges over caves,
     * ravines, and overhangs.
     */
    public static boolean isRoadCandidateSurface(WorldGenLevel world, BlockPos pos) {
        if (!isLoaded(world, pos) || !isNotWaterBlock(world, pos)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        String id = lower(state);
        if (!(isTerrainLike(state) || isRoadSurfaceLike(id))) {
            return false;
        }
        if (!isOpenOrOverlay(world.getBlockState(pos.above()))) {
            return false;
        }
        BlockState below = world.getBlockState(pos.below());
        String belowId = lower(below);
        return below != null
                && !below.isAir()
                && !isFluidBlock(below, belowId)
                && !isReplaceableOverlay(below)
                && !isTreeMaterial(below)
                && !containsId(belowId, "structure_void")
                && !containsId(belowId, "barrier");
    }

    public static boolean isReplaceableOverlay(BlockState state) {
        if (state == null || state.isAir()) {
            return true;
        }
        if (GooskerRoadSurfaceRules.shouldPreserveOverlay(state)) {
            return true;
        }
        String id = lower(state);
        return id.contains("leaf_litter") || id.contains("fallen_leaves")
                || id.contains("moss_carpet") || id.contains("petals")
                || id.contains("vine") || id.contains("lichen")
                || id.contains("sapling") || id.contains("bush");
    }

    private static boolean isOpenOrOverlay(BlockState state) {
        if (state == null || state.isAir() || isReplaceableOverlay(state) || isTreeMaterial(state)) {
            return true;
        }
        return containsId(lower(state), "structure_void");
    }

    /** Detects terrain-like roof coverings by inspecting their supporting column. */
    private static boolean hasBuildingUnderfoot(WorldGenLevel world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy >= -BUILDING_SCAN_DOWN; dy--) {
                    BlockPos scan = pos.offset(dx, dy, dz);
                    if (!isLoaded(world, scan)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(scan);
                    String id = lower(state);
                    if (isDistinctiveBuildingBlock(state)) {
                        return true;
                    }
                    if (isStrongBuildingBlock(state) && !isRoadDecorationLike(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasNearbyBuilding(WorldGenLevel world, BlockPos pos, int radius) {
        if (world == null || pos == null) {
            return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int strong = 0;
                for (int dy = -BUILDING_SCAN_DOWN; dy <= BUILDING_SCAN_UP; dy++) {
                    BlockPos scan = pos.offset(dx, dy, dz);
                    if (!isLoaded(world, scan)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(scan);
                    String id = lower(state);
                    if (containsId(id, "structure_void") || isFluidBlock(state, id)
                            || isTerrainLike(state) || isTreeMaterial(state)) {
                        continue;
                    }
                    if (isDistinctiveBuildingBlock(state)) {
                        return true;
                    }
                    if (isStrongBuildingBlock(state) && !isRoadSurfaceLike(id)
                            && !isRoadDecorationLike(id) && ++strong >= 3) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isLoaded(WorldGenLevel world, BlockPos pos) {
        try {
            return world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isFluidBlock(BlockState state, String id) {
        if (state == null) {
            return false;
        }
        if (containsId(id, "water") || containsId(id, "lava")) {
            return true;
        }
        try {
            return !state.getFluidState().isEmpty() || state.getFluidState().is(FluidTags.WATER);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isTerrainLike(BlockState state) {
        if (state == null) {
            return false;
        }
        return TERRAIN_CACHE.computeIfAbsent(state, value -> {
            String id = lower(value);
            if (id.contains("terrainslabs:") || id.contains("terrain_slabs:")) {
                return true;
            }
            String[] names = {
                    "grass_block", "dirt_path", "coarse_dirt", "rooted_dirt", "dirt", "podzol",
                    "mycelium", "mud", "clay", "gravel", "sand", "moss_block", "snow_block",
                    "dripstone_block", "calcite", "tuff", "andesite", "diorite", "granite",
                    "deepslate", "netherrack", "soul_sand", "soul_soil", "end_stone", "terracotta"
            };
            for (String name : names) {
                if (id.contains(name)) {
                    return true;
                }
            }
            return id.contains("stone") && !id.contains("cobblestone")
                    && !id.contains("brick") && !id.contains("cut_") && !id.contains("smooth_")
                    && !id.contains("slab")
                    && !id.contains("stairs") && !id.contains("wall")
                    && !id.contains("chiseled") && !id.contains("polished");
        });
    }

    public static boolean isStrongBuildingBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        return BUILDING_CACHE.computeIfAbsent(state, value -> {
            String id = lower(value);
            if (isIgnorable(id) || isTerrainLike(value) || isTreeMaterial(value)) {
                return false;
            }
            String[] names = {
                    "planks", "brick", "cobblestone", "slab", "stairs", "wall", "fence", "glass",
                    "pane", "wool", "concrete", "glazed", "purpur", "quartz", "cut_sandstone",
                    "smooth_sandstone", "polished", "chiseled", "stripped_", "tiles", "shingles"
            };
            for (String name : names) {
                if (id.contains(name)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static boolean isDistinctiveBuildingBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        return DISTINCTIVE_CACHE.computeIfAbsent(state, value -> {
            String id = lower(value);
            if (isIgnorable(id)) {
                return false;
            }
            String[] names = {
                    "door", "trapdoor", "barrel", "chest", "bookshelf", "chain", "iron_bars", "anvil",
                    "bell", "bed", "carpet", "farmland", "wheat", "carrots", "potatoes", "beetroots",
                    "hay_block", "composter", "crafting_table", "fletching_table", "smithing_table",
                    "cartography_table", "loom", "stonecutter", "grindstone", "smoker", "blast_furnace",
                    "furnace", "campfire", "cauldron", "flower_pot", "lectern", "lantern"
            };
            for (String name : names) {
                if (id.contains(name)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static boolean isTreeMaterial(BlockState state) {
        String id = lower(state);
        return id.contains("_leaves") || id.contains("leaves[") || id.contains("_log")
                || id.contains("_wood") || id.contains("_stem") || id.contains("_hyphae")
                || id.contains("mushroom_stem");
    }

    private static boolean isRoadSurfaceLike(String id) {
        return id.contains("gravel") || id.contains("dirt_path") || id.contains("cobblestone")
                || id.contains("stone_brick") || id.contains("mossy_") || id.contains("path")
                || id.contains("terrainslabs:") || id.contains("terrain_slabs:");
    }

    private static boolean isRoadDecorationLike(String id) {
        return id.contains("fence") || id.contains("wall") || id.contains("lantern") || id.contains("torch");
    }

    private static boolean isIgnorable(String id) {
        return id.contains("air") || id.contains("water") || id.contains("lava")
                || containsId(id, "barrier") || containsId(id, "structure_void");
    }

    private static String lower(BlockState state) {
        return state == null ? "" : String.valueOf(state).toLowerCase(Locale.ROOT);
    }

    private static boolean containsId(String id, String needle) {
        return id.contains("minecraft:" + needle) || id.contains("{" + needle + "}") || id.contains(needle);
    }
}
