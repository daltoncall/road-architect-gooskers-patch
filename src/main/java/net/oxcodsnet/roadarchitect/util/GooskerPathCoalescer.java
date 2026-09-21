package net.oxcodsnet.roadarchitect.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

public final class GooskerPathCoalescer {
    private static final double MERGE_RADIUS = 18.0;
    private static final double MERGE_RADIUS_SQ = MERGE_RADIUS * MERGE_RADIUS;
    private static final int MIN_PARALLEL_RUN = 12;
    private static final int EASE_LENGTH = 40;
    private static final int TANGENT_RADIUS = 5;
    private static final double MAX_ANGLE_DEG = 24.0;
    private static final int GRID = 16;
    private static final int CELL_SEARCH_RADIUS = 2;

    private GooskerPathCoalescer() {
    }

    public static void coalesceReadyPaths(Object object, Map map) {
        if (object == null || map == null || map.isEmpty()) {
            return;
        }
        try {
            Class<?> clazz = object.getClass();
            Method method = clazz.getMethod("allStatuses", new Class[0]);
            Method method2 = clazz.getMethod("getPath", String.class);
            Class<?> clazz2 = Class.forName("net.oxcodsnet.roadarchitect.storage.PathStorage$Status", false, clazz.getClassLoader());
            Method method3 = clazz.getMethod("updatePath", String.class, List.class, clazz2);
            Enum enum_ = Enum.valueOf(clazz2.asSubclass(Enum.class), "READY");
            LinkedHashMap<Object, Object> linkedHashMap = new LinkedHashMap<>((Map<?, ?>)method.invoke(object));
            HashSet<String> hashSet = new HashSet<String>();
            for (Object key : map.keySet()) {
                hashSet.add(String.valueOf(key));
            }
            ArrayList<Candidate> candidates = new ArrayList<>();
            for (Map.Entry<Object, Object> entry : linkedHashMap.entrySet()) {
                Object storedPath = method2.invoke(object, entry.getKey());
                if (entry.getValue() != enum_ || hashSet.contains(entry.getKey()) || !(storedPath instanceof List) || ((List<?>)storedPath).size() < MIN_PARALLEL_RUN) continue;
                candidates.add(new Candidate((String)entry.getKey(), (List<BlockPos>)storedPath));
            }
            ArrayList<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) {
                keys.add(String.valueOf(key));
            }
            Collections.sort(keys);
            for (String key : keys) {
                Object value = map.get(key);
                if (!(value instanceof List)) continue;
                List<BlockPos> list = (List<BlockPos>)value;
                if (list.size() < MIN_PARALLEL_RUN) {
                    candidates.add(new Candidate(key, list));
                    continue;
                }
                Merge merge = null;
                for (Candidate candidate : candidates) {
                    Merge merge2;
                    if (candidate.key.equals(key) || !GooskerPathCoalescer.bboxNear(list, candidate.path, (int)MERGE_RADIUS + 8) || (merge2 = GooskerPathCoalescer.findBestRun(list, candidate.path)) == null || merge != null && !(merge2.score > merge.score)) continue;
                    merge = merge2.withCandidate(candidate);
                }
                List<BlockPos> result = list;
                if (merge != null) {
                    result = GooskerPathCoalescer.merge(list, merge.candidate.path, merge);
                    map.put(key, result);
                    method3.invoke(object, key, result, enum_);
                }
                candidates.add(new Candidate(key, result));
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private static Merge findBestRun(List<BlockPos> list, List<BlockPos> list2) {
        int n;
        SpatialIndex spatialIndex = new SpatialIndex(list2);
        int n2 = list.size();
        int[] nArray = new int[n2];
        double[] dArray = new double[n2];
        boolean[] blArray = new boolean[n2];
        Arrays.fill(nArray, -1);
        Arrays.fill(dArray, Double.POSITIVE_INFINITY);
        for (int i = 0; i < n2; ++i) {
            double d;
            BlockPos blockPos = list.get(i);
            n = spatialIndex.nearest(blockPos);
            if (n < 0 || (d = GooskerPathCoalescer.dist2(blockPos, list2.get(n))) > MERGE_RADIUS_SQ || GooskerPathCoalescer.angleDeg(GooskerPathCoalescer.tangent(list, i), GooskerPathCoalescer.tangent(list2, n)) > MAX_ANGLE_DEG) continue;
            nArray[i] = n;
            dArray[i] = d;
            blArray[i] = true;
        }
        Merge merge = null;
        int n3 = 0;
        while (n3 < n2) {
            int n4;
            while (n3 < n2 && !blArray[n3]) {
                ++n3;
            }
            if (n3 >= n2) break;
            n = n3;
            int n5 = 0;
            double d = 0.0;
            int n6 = 0;
            while (n3 < n2) {
                if (blArray[n3]) {
                    n5 = 0;
                    d += Math.sqrt(dArray[n3]);
                    ++n6;
                } else if (++n5 > 3) {
                    n3 -= n5;
                    break;
                }
                ++n3;
            }
            for (n4 = Math.min(n2 - 1, n3 - 1); n4 >= n && !blArray[n4]; --n4) {
            }
            int n7 = n4 - n + 1;
            if (n7 >= MIN_PARALLEL_RUN && n6 > 0) {
                int n8 = GooskerPathCoalescer.nearestFirst(nArray, n, n4);
                int n9 = GooskerPathCoalescer.nearestLast(nArray, n, n4);
                if (n8 >= 0 && n9 >= 0) {
                    int n10 = n9 >= n8 ? 1 : -1;
                    double d2 = d / (double)n6;
                    double d3 = (double)n7 * 10.0 - d2;
                    Merge merge2 = new Merge(n, n4, n8, n9, n10, d3, null);
                    if (merge == null || merge2.score > merge.score) {
                        merge = merge2;
                    }
                }
            }
            n3 = Math.max(n3 + 1, n4 + 1);
        }
        return merge;
    }

    private static List<BlockPos> merge(List<BlockPos> list, List<BlockPos> list2, Merge merge) {
        int n;
        int n2;
        int n3;
        int n4 = list.size();
        if (n4 < MIN_PARALLEL_RUN + 2) {
            return list;
        }
        int n5 = Math.min(EASE_LENGTH, Math.max(1, (n4 - MIN_PARALLEL_RUN) / 2));
        int n6 = Math.max(merge.start, n5);
        int n7 = Math.min(merge.end, n4 - 1 - n5);
        if (n7 - n6 + 1 < MIN_PARALLEL_RUN) {
            return list;
        }
        ArrayList<BlockPos> arrayList = new ArrayList<BlockPos>(list);
        int n8 = Math.max(0, n6 - EASE_LENGTH);
        int n9 = Math.min(n4 - 1, n7 + EASE_LENGTH);
        int n10 = GooskerPathCoalescer.clamp(merge.jStart + merge.orientation * (n6 - merge.start), 0, list2.size() - 1);
        int n11 = GooskerPathCoalescer.nearestLocal(list.get(n6), list2, n10, 8);
        for (n3 = n8; n3 < n6; ++n3) {
            double d = Math.max(1.0, (double)(n6 - n8));
            double d2 = (double)(n3 - n8) / d;
            d2 = GooskerPathCoalescer.smoothstep(d2);
            int n12 = GooskerPathCoalescer.clamp(n11 - merge.orientation * (n6 - n3), 0, list2.size() - 1);
            arrayList.set(n3, GooskerPathCoalescer.blend(list.get(n3), list2.get(n12), d2));
        }
        n3 = n11;
        for (n2 = n6; n2 <= n7; ++n2) {
            n = GooskerPathCoalescer.clamp(n11 + merge.orientation * (n2 - n6), 0, list2.size() - 1);
            int n13 = GooskerPathCoalescer.nearestLocal(list.get(n2), list2, n, 8);
            n13 = merge.orientation > 0 ? Math.max(n3, n13) : Math.min(n3, n13);
            n3 = n13;
            arrayList.set(n2, list2.get(n13));
        }
        n2 = n3;
        for (n = n7 + 1; n <= n9; ++n) {
            double d = Math.max(1.0, (double)(n9 - n7));
            double d3 = (double)(n9 - n) / d;
            d3 = GooskerPathCoalescer.smoothstep(d3);
            int n14 = GooskerPathCoalescer.clamp(n2 + merge.orientation * (n - n7), 0, list2.size() - 1);
            arrayList.set(n, GooskerPathCoalescer.blend(list.get(n), list2.get(n14), d3));
        }
        return arrayList;
    }

    private static BlockPos blend(BlockPos blockPos, BlockPos blockPos2, double d) {
        int n = (int)Math.round((double)blockPos.getX() + (double)(blockPos2.getX() - blockPos.getX()) * d);
        int n2 = (int)Math.round((double)blockPos.getY() + (double)(blockPos2.getY() - blockPos.getY()) * d);
        int n3 = (int)Math.round((double)blockPos.getZ() + (double)(blockPos2.getZ() - blockPos.getZ()) * d);
        return new BlockPos(n, n2, n3);
    }

    private static double smoothstep(double d) {
        d = Math.max(0.0, Math.min(1.0, d));
        return d * d * (3.0 - 2.0 * d);
    }

    private static int nearestLocal(BlockPos blockPos, List<BlockPos> list, int n, int n2) {
        int n3 = Math.max(0, n - n2);
        int n4 = Math.min(list.size() - 1, n + n2);
        int n5 = n;
        double d = Double.POSITIVE_INFINITY;
        for (int i = n3; i <= n4; ++i) {
            double d2 = GooskerPathCoalescer.dist2(blockPos, list.get(i));
            if (!(d2 < d)) continue;
            d = d2;
            n5 = i;
        }
        return n5;
    }

    private static int nearestFirst(int[] nArray, int n, int n2) {
        for (int i = n; i <= n2; ++i) {
            if (nArray[i] < 0) continue;
            return nArray[i];
        }
        return -1;
    }

    private static int nearestLast(int[] nArray, int n, int n2) {
        for (int i = n2; i >= n; --i) {
            if (nArray[i] < 0) continue;
            return nArray[i];
        }
        return -1;
    }

    private static double[] tangent(List<BlockPos> list, int n) {
        int n2 = Math.max(0, n - 5);
        int n3 = Math.min(list.size() - 1, n + 5);
        return new double[]{list.get(n3).getX() - list.get(n2).getX(), list.get(n3).getZ() - list.get(n2).getZ()};
    }

    private static double angleDeg(double[] dArray, double[] dArray2) {
        double d = Math.hypot(dArray[0], dArray[1]);
        double d2 = Math.hypot(dArray2[0], dArray2[1]);
        if (d < 1.0E-6 || d2 < 1.0E-6) {
            return 180.0;
        }
        double d3 = (dArray[0] * dArray2[0] + dArray[1] * dArray2[1]) / (d * d2);
        d3 = Math.max(-1.0, Math.min(1.0, d3));
        double d4 = Math.toDegrees(Math.acos(d3));
        return Math.min(d4, 180.0 - d4);
    }

    private static double dist2(BlockPos blockPos, BlockPos blockPos2) {
        double d = blockPos.getX() - blockPos2.getX();
        double d2 = blockPos.getZ() - blockPos2.getZ();
        return d * d + d2 * d2;
    }

    private static boolean bboxNear(List<BlockPos> list, List<BlockPos> list2, int n) {
        int[] nArray;
        int[] nArray2 = GooskerPathCoalescer.bbox(list);
        return nArray2[0] - n <= (nArray = GooskerPathCoalescer.bbox(list2))[2] && nArray2[2] + n >= nArray[0] && nArray2[1] - n <= nArray[3] && nArray2[3] + n >= nArray[1];
    }

    private static int[] bbox(List<BlockPos> list) {
        int n = Integer.MAX_VALUE;
        int n2 = Integer.MAX_VALUE;
        int n3 = Integer.MIN_VALUE;
        int n4 = Integer.MIN_VALUE;
        for (BlockPos blockPos : list) {
            n = Math.min(n, blockPos.getX());
            n3 = Math.max(n3, blockPos.getX());
            n2 = Math.min(n2, blockPos.getZ());
            n4 = Math.max(n4, blockPos.getZ());
        }
        return new int[]{n, n2, n3, n4};
    }

    private static int clamp(int n, int n2, int n3) {
        return Math.max(n2, Math.min(n3, n));
    }

    private record Candidate(String key, List<BlockPos> path) {
    }

    private record Merge(int start, int end, int jStart, int jEnd, int orientation, double score, Candidate candidate) {
        Merge withCandidate(Candidate candidate) {
            return new Merge(this.start, this.end, this.jStart, this.jEnd, this.orientation, this.score, candidate);
        }
    }

    private static final class SpatialIndex {
        private final List<BlockPos> path;
        private final Map<Long, int[]> cells = new HashMap<Long, int[]>();

        SpatialIndex(List<BlockPos> list) {
            this.path = list;
            HashMap<Long, List> hashMap = new HashMap<Long, List>();
            for (int i = 0; i < list.size(); ++i) {
                BlockPos object = list.get(i);
                long l2 = SpatialIndex.key(SpatialIndex.floorDiv(object.getX(), 16), SpatialIndex.floorDiv(object.getZ(), 16));
                hashMap.computeIfAbsent(l2, l -> new ArrayList()).add(i);
            }
            for (Map.Entry entry : hashMap.entrySet()) {
                int[] nArray = new int[((List)entry.getValue()).size()];
                for (int i = 0; i < nArray.length; ++i) {
                    nArray[i] = (Integer)((List)entry.getValue()).get(i);
                }
                this.cells.put((Long)entry.getKey(), nArray);
            }
        }

        int nearest(BlockPos blockPos) {
            int n = SpatialIndex.floorDiv(blockPos.getX(), 16);
            int n2 = SpatialIndex.floorDiv(blockPos.getZ(), 16);
            int n3 = -1;
            double d = MERGE_RADIUS_SQ + 1.0;
            for (int i = -CELL_SEARCH_RADIUS; i <= CELL_SEARCH_RADIUS; ++i) {
                for (int j = -CELL_SEARCH_RADIUS; j <= CELL_SEARCH_RADIUS; ++j) {
                    int[] nArray = this.cells.get(SpatialIndex.key(n + i, n2 + j));
                    if (nArray == null) continue;
                    for (int n4 : nArray) {
                        double d2 = GooskerPathCoalescer.dist2(blockPos, this.path.get(n4));
                        if (!(d2 < d)) continue;
                        d = d2;
                        n3 = n4;
                    }
                }
            }
            return n3;
        }

        private static int floorDiv(int n, int n2) {
            return Math.floorDiv(n, n2);
        }

        private static long key(int n, int n2) {
            return (long)n << 32 ^ (long)n2 & 0xFFFFFFFFL;
        }
    }
}
