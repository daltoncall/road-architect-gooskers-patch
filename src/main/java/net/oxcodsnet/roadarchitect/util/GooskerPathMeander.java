package net.oxcodsnet.roadarchitect.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.oxcodsnet.roadarchitect.util.CacheManager;
import net.oxcodsnet.roadarchitect.util.PathFinder;

public final class GooskerPathMeander {
    private static volatile Field WORLD_FIELD;

    private GooskerPathMeander() {
    }

    public static List<BlockPos> findPath(PathFinder pathFinder, String string, String string2) {
        List<BlockPos> list = pathFinder.findPath(string, string2);
        return GooskerPathMeander.meander(pathFinder, list, string, string2);
    }

    private static List<BlockPos> meander(PathFinder pathFinder, List<BlockPos> list, String string, String string2) {
        double d;
        if (list == null || list.size() < 20) {
            return list;
        }
        BlockPos blockPos = list.get(0);
        BlockPos blockPos2 = list.get(list.size() - 1);
        double d2 = blockPos2.getX() - blockPos.getX();
        double d3 = Math.hypot(d2, d = (double)(blockPos2.getZ() - blockPos.getZ()));
        if (d3 < 96.0) {
            return list;
        }
        double[] dArray = GooskerPathMeander.cumulativeHorizontalDistance(list);
        double d4 = dArray[dArray.length - 1];
        if (d4 <= 0.0) {
            return list;
        }
        double d5 = d3 / d4;
        if (d5 < 0.68) {
            return list;
        }
        ServerLevel serverLevel = GooskerPathMeander.getWorld(pathFinder);
        if (serverLevel == null) {
            return list;
        }
        long l = GooskerPathMeander.mix64((long)string.hashCode() << 32 ^ (long)string2.hashCode() & 0xFFFFFFFFL);
        double d6 = GooskerPathMeander.phase(l, 9);
        double d7 = GooskerPathMeander.phase(l, 31);
        ArrayList<BlockPos> arrayList = new ArrayList<BlockPos>(list.size());
        BlockPos blockPos3 = null;
        for (int i = 0; i < list.size(); ++i) {
            double d8;
            BlockPos blockPos4 = list.get(i);
            double d9 = dArray[i] / d4;
            double d10 = Math.pow(Math.sin(Math.PI * d9), 0.72);
            double d11 = dArray[i];
            double d12 = d10 * (13.0 * Math.sin(Math.PI * 2 * d11 / 300.0 + d6) + 6.0 * Math.sin(Math.PI * 2 * d11 / 620.0 + d7));
            BlockPos blockPos5 = list.get(Math.max(0, i - 2));
            BlockPos blockPos6 = list.get(Math.min(list.size() - 1, i + 2));
            double d13 = blockPos6.getX() - blockPos5.getX();
            double d14 = Math.hypot(d13, d8 = (double)(blockPos6.getZ() - blockPos5.getZ()));
            if (d14 < 0.001) {
                d13 = d2;
                d8 = d;
                d14 = d3;
            }
            double d15 = -d8 / d14;
            double d16 = d13 / d14;
            BlockPos blockPos7 = GooskerPathMeander.terrainFit(serverLevel, blockPos4, blockPos3, d15, d16, d12);
            if (blockPos3 != null && blockPos7.getX() == blockPos3.getX() && blockPos7.getZ() == blockPos3.getZ()) continue;
            arrayList.add(blockPos7);
            blockPos3 = blockPos7;
        }
        return arrayList.size() >= 2 ? arrayList : list;
    }

    private static BlockPos terrainFit(ServerLevel serverLevel, BlockPos blockPos, BlockPos blockPos2, double d, double d2, double d3) {
        double[] dArray = new double[]{1.0, 0.84, 0.68, 0.5, 0.32, 0.16, 0.0};
        int n = blockPos.getY();
        BlockPos blockPos3 = new BlockPos(blockPos.getX(), CacheManager.getHeight(serverLevel, blockPos.getX(), blockPos.getZ()), blockPos.getZ());
        for (double d4 : dArray) {
            double d5 = d3 * d4;
            int n2 = (int)Math.round((double)blockPos.getX() + d * d5);
            int n3 = (int)Math.round((double)blockPos.getZ() + d2 * d5);
            int n4 = CacheManager.getHeight(serverLevel, n2, n3);
            if (n > 63 && n4 <= 63 && d4 > 0.0 || Math.abs(n4 - n) > 8 && d4 > 0.0 || blockPos2 != null && Math.abs(n4 - blockPos2.getY()) > 3) continue;
            return new BlockPos(n2, n4, n3);
        }
        if (blockPos2 != null && Math.abs(blockPos3.getY() - blockPos2.getY()) > 3) {
            int n5 = blockPos2.getY() + Integer.signum(blockPos3.getY() - blockPos2.getY()) * 3;
            return new BlockPos(blockPos.getX(), n5, blockPos.getZ());
        }
        return blockPos3;
    }

    private static double[] cumulativeHorizontalDistance(List<BlockPos> list) {
        double[] dArray = new double[list.size()];
        for (int i = 1; i < list.size(); ++i) {
            BlockPos blockPos = list.get(i - 1);
            BlockPos blockPos2 = list.get(i);
            dArray[i] = dArray[i - 1] + Math.hypot(blockPos2.getX() - blockPos.getX(), blockPos2.getZ() - blockPos.getZ());
        }
        return dArray;
    }

    private static ServerLevel getWorld(PathFinder pathFinder) {
        try {
            Field field = WORLD_FIELD;
            if (field == null) {
                field = PathFinder.class.getDeclaredField("world");
                field.setAccessible(true);
                WORLD_FIELD = field;
            }
            return (ServerLevel)field.get(pathFinder);
        }
        catch (Throwable throwable) {
            return null;
        }
    }

    private static double phase(long l, int n) {
        return (double)(l >>> n & 0xFFFFL) / 65535.0 * Math.PI * 2.0;
    }

    private static long mix64(long l) {
        l = (l ^ l >>> 33) * -49064778989728563L;
        l = (l ^ l >>> 33) * -4265267296055464877L;
        return l ^ l >>> 33;
    }
}

