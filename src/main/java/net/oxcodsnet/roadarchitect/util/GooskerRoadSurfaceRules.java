package net.oxcodsnet.roadarchitect.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class GooskerRoadSurfaceRules {
    private static volatile Field preparationBackupField;
    private static volatile Method originalStateMethod;

    private GooskerRoadSurfaceRules() {
    }

    public static boolean isIceAtOrBelow(WorldGenLevel worldGenLevel, BlockPos blockPos) {
        if (worldGenLevel == null || blockPos == null) {
            return false;
        }
        try {
            int n = blockPos.getX();
            int n2 = blockPos.getY();
            int n3 = blockPos.getZ();
            if (!worldGenLevel.hasChunk(n >> 4, n3 >> 4)) {
                return false;
            }
            BlockState blockState = worldGenLevel.getBlockState(blockPos);
            if (GooskerRoadSurfaceRules.isVanillaIce(blockState)) {
                return true;
            }
            BlockState blockState2 = worldGenLevel.getBlockState(new BlockPos(n, n2 - 1, n3));
            return GooskerRoadSurfaceRules.isVanillaIce(blockState2);
        }
        catch (Throwable throwable) {
            return false;
        }
    }

    private static boolean isVanillaIce(BlockState blockState) {
        if (blockState == null) {
            return false;
        }
        String string = String.valueOf(blockState).toLowerCase(Locale.ROOT);
        return GooskerRoadSurfaceRules.hasExactMinecraftId(string, "ice") || GooskerRoadSurfaceRules.hasExactMinecraftId(string, "packed_ice") || GooskerRoadSurfaceRules.hasExactMinecraftId(string, "blue_ice") || GooskerRoadSurfaceRules.hasExactMinecraftId(string, "frosted_ice");
    }

    private static boolean hasExactMinecraftId(String string, String string2) {
        String string3 = "minecraft:" + string2;
        return string.contains("{" + string3 + "}") || string.contains("{" + string3 + "}[") || string.contains("block{" + string3 + "}") || string.equals(string3) || string.startsWith(string3 + "[");
    }

    public static boolean shouldPreserveOverlay(BlockState blockState) {
        if (blockState == null) {
            return false;
        }
        String string = String.valueOf(blockState).toLowerCase(Locale.ROOT);
        if (GooskerRoadSurfaceRules.hasExactMinecraftId(string, "snow")) {
            return true;
        }
        return string.contains("flower") || string.contains("dandelion") || string.contains("poppy") || string.contains("orchid") || string.contains("allium") || string.contains("azure_bluet") || string.contains("tulip") || string.contains("oxeye_daisy") || string.contains("cornflower") || string.contains("lily_of_the_valley") || string.contains("wither_rose") || string.contains("torchflower") || string.contains("pitcher_plant") || string.contains("pink_petals") || GooskerRoadSurfaceRules.hasExactMinecraftId(string, "grass") || string.contains("tall_grass") || string.contains("short_grass") || string.contains("fern") || string.contains("dead_bush");
    }

    public static BlockState preserveOriginalProperties(BlockPos blockPos, BlockState blockState) {
        if (blockPos == null || blockState == null) {
            return blockState;
        }
        try {
            Block block;
            Object object;
            Object object2;
            Field field = preparationBackupField;
            if (field == null) {
                object2 = Class.forName("net.oxcodsnet.roadarchitect.worldgen.RoadFeature");
                field = ((Class)object2).getDeclaredField("PREPARATION_BACKUP");
                field.setAccessible(true);
                preparationBackupField = field;
            }
            if (!((object2 = field.get(null)) instanceof Map)) {
                return blockState;
            }
            Object v = ((Map)object2).get(blockPos.asLong());
            if (v == null) {
                return blockState;
            }
            Method method = originalStateMethod;
            if (method == null || method.getDeclaringClass() != v.getClass()) {
                method = v.getClass().getDeclaredMethod("originalState", new Class[0]);
                method.setAccessible(true);
                originalStateMethod = method;
            }
            if (!((object = method.invoke(v, new Object[0])) instanceof BlockState)) {
                return blockState;
            }
            BlockState blockState2 = (BlockState)object;
            Block block2 = blockState2.getBlock();
            if (block2 == (block = blockState.getBlock()) || block2 != null && block2.equals(block)) {
                return blockState2;
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
        return blockState;
    }
}

