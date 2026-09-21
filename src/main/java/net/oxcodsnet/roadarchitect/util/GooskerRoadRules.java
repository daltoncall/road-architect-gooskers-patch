package net.oxcodsnet.roadarchitect.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.oxcodsnet.roadarchitect.util.BiomeSelectorUtil;
import net.oxcodsnet.roadarchitect.util.GooskerRoadVegetation;
import net.oxcodsnet.roadarchitect.worldgen.RoadFeature;
import net.oxcodsnet.roadarchitect.worldgen.RoadFeatureConfig;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;
import net.oxcodsnet.roadarchitect.worldgen.style.decoration.BuoyDecoration;

public final class GooskerRoadRules {
    private static final int CENTERLINE_SNAP_RANGE = 3;

    private GooskerRoadRules() {
    }

    public static int adjustLoadedHeight(ServerLevel serverLevel, ChunkAccess chunkAccess, int n, int n2, int n3) {
        BlockState blockState;
        BlockState blockState2;
        int n4;
        if (serverLevel == null || chunkAccess == null) {
            return n3;
        }
        ChunkPos chunkPos = chunkAccess.getPos();
        if (chunkPos == null) {
            return n3;
        }
        int n5 = (chunkPos.x << 4) + n;
        int n6 = (chunkPos.z << 4) + n2;
        int n7 = Math.max(serverLevel.getMinBuildHeight(), n3 - 4);
        int n8 = n3 + 4;
        for (n4 = n7; n4 <= n8; ++n4) {
            BlockPos blockPos = new BlockPos(n5, n4, n6);
            blockState2 = chunkAccess.getBlockState(blockPos);
            if (!GooskerRoadRules.isBarrier(blockState2) && !GooskerRoadRules.isPreparationMarker(blockState2)) continue;
            serverLevel.removeBlock(blockPos, false);
        }
        int n9 = Math.max(serverLevel.getMinBuildHeight(), n4 - 48);
        blockState2 = chunkAccess.getBlockState(new BlockPos(n5, n4, n6));
        if (!GooskerRoadVegetation.isTreeMaterial(blockState2)) {
            return n3;
        }
        for (n4 = n3 - 1; n4 >= n9 && (blockState = chunkAccess.getBlockState(new BlockPos(n5, n4, n6))) != null; --n4) {
            if (blockState.isAir() || GooskerRoadVegetation.isTreeMaterial(blockState) || GooskerRoadRules.isSoftPlant(blockState)) continue;
            return n4 + 1;
        }
        return n3;
    }

    private static boolean isBarrier(BlockState blockState) {
        if (blockState == null) {
            return false;
        }
        String string = String.valueOf(blockState).toLowerCase(Locale.ROOT);
        return string.contains("minecraft:barrier") || string.contains("block{minecraft:barrier}");
    }

    private static boolean isTreeMaterial(BlockState blockState) {
        if (blockState == null) {
            return false;
        }
        String string = String.valueOf(blockState).toLowerCase(Locale.ROOT);
        return string.contains("_leaves") || string.contains("leaves[") || string.contains("_log") || string.contains("_wood") || string.contains("_stem") || string.contains("_hyphae") || string.contains("mushroom_stem");
    }

    private static boolean isSoftPlant(BlockState blockState) {
        if (blockState == null) {
            return false;
        }
        String string = String.valueOf(blockState).toLowerCase(Locale.ROOT);
        return string.contains("vine") || string.contains("lichen") || string.contains("moss_carpet") || string.contains("azalea") || string.contains("bush") || string.contains("sapling");
    }

    private static boolean isPreparationMarker(BlockState blockState) {
        if (blockState == null) {
            return false;
        }
        return String.valueOf(blockState).toLowerCase(Locale.ROOT).contains("structure_void");
    }

    private static boolean isOverlay(BlockState blockState) {
        if (blockState == null) {
            return false;
        }
        String string = String.valueOf(blockState).toLowerCase(Locale.ROOT);
        return string.contains("snow") || string.contains("flower") || string.contains("short_grass") || string.contains("tall_grass") || string.contains("fern") || string.contains("petals") || string.contains("carpet") || string.contains("vine") || string.contains("lichen") || string.contains("bush");
    }

    private static boolean isSurfaceAt(WorldGenLevel worldGenLevel, int n, int n2, int n3) {
        BlockPos blockPos = new BlockPos(n, n2, n3);
        BlockState blockState = worldGenLevel.getBlockState(blockPos);
        if (GooskerRoadRules.isPreparationMarker(blockState)) {
            return true;
        }
        if (blockState == null || blockState.isAir() || GooskerRoadRules.isOverlay(blockState) || GooskerRoadVegetation.isTreeMaterial(blockState)) {
            return false;
        }
        BlockState blockState2 = worldGenLevel.getBlockState(new BlockPos(n, n2 + 1, n3));
        return blockState2 == null || blockState2.isAir() || GooskerRoadRules.isOverlay(blockState2) || GooskerRoadVegetation.isTreeMaterial(blockState2) || GooskerRoadRules.isPreparationMarker(blockState2);
    }

    private static BlockPos snapCenterline(WorldGenLevel worldGenLevel, BlockPos blockPos) {
        if (worldGenLevel == null || blockPos == null) {
            return blockPos;
        }
        int n = blockPos.getX();
        int n2 = blockPos.getY();
        int n3 = blockPos.getZ();
        for (int i = 0; i <= 3; ++i) {
            int n4;
            int n5 = n2 - i;
            if (GooskerRoadRules.isSurfaceAt(worldGenLevel, n, n5, n3)) {
                return new BlockPos(n, n5, n3);
            }
            if (i == 0 || !GooskerRoadRules.isSurfaceAt(worldGenLevel, n, n4 = n2 + i, n3)) continue;
            return new BlockPos(n, n4, n3);
        }
        return blockPos;
    }

    public static boolean strongBuildingOrWater(BlockState blockState) {
        if (blockState == null) {
            return false;
        }
        try {
            if (blockState.getFluidState().is(FluidTags.WATER)) {
                return true;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return RoadStructureProtection.isStrongBuildingBlock(blockState);
    }

    public static boolean forbiddenBiomeOrWater(Holder<Biome> holder, List<HolderSet<Biome>> list) {
        return BiomeSelectorUtil.matches(holder, list);
    }

    public static void noBuoy(WorldGenLevel worldGenLevel, BlockPos blockPos, RandomSource randomSource) {
    }

    public static void noBuoyWithInstance(BuoyDecoration buoyDecoration, WorldGenLevel worldGenLevel, BlockPos blockPos, RandomSource randomSource) {
    }

    public static void buildRoadStripeReconciled(WorldGenLevel worldGenLevel, List<BlockPos> list, int n, RandomSource randomSource, RoadFeatureConfig.GenerationPhase generationPhase) {
        if (worldGenLevel == null || list == null || list.isEmpty()) {
            return;
        }
        ArrayList<BlockPos> arrayList = new ArrayList<BlockPos>(list.size());
        for (BlockPos blockPos : list) {
            arrayList.add(GooskerRoadVegetation.keepCenterline(worldGenLevel, blockPos));
        }
        RoadFeature.buildRoadStripe(worldGenLevel, arrayList, n, randomSource, generationPhase);
    }
}
