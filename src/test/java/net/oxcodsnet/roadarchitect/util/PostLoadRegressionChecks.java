package net.oxcodsnet.roadarchitect.util;

import java.util.ArrayList;
import java.util.List;

/** Executable checks for marker ownership across the one-point tick budget. */
public final class PostLoadRegressionChecks {
    public static void main(String[] args) {
        double[][] cases = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10},
            {0, 1.4142, 2.8284, 4.2426, 5.6568, 7.0710, 8.4852},
            {0, 0, 1, 1, 2, 4, 4, 8},
            {0},
            {0, 30, 70, 100}
        };
        int checked = 0;
        for (double[] prefix : cases) {
            for (int step = 1; step <= 15; step++) {
                for (int phase = 0; phase < step; phase++) {
                    List<PathDecorUtil.Marker> whole = PathDecorUtil.markersInWindow(prefix, 0, prefix.length, step, phase);
                    List<PathDecorUtil.Marker> sliced = new ArrayList<>();
                    for (int i = 0; i < prefix.length; i++) {
                        sliced.addAll(PathDecorUtil.markersInWindow(prefix, i, i + 1, step, phase));
                    }
                    if (!whole.equals(sliced)) throw new AssertionError("One-point markers differ: " + whole + " vs " + sliced);
                    for (var marker : whole) {
                        double distance = phase + marker.k() * step;
                        int expected = 0;
                        while (expected < prefix.length && prefix[expected] < distance) expected++;
                        if (expected != marker.index()) throw new AssertionError("Wrong marker point");
                    }
                    checked++;
                }
            }
        }
        if (PathDecorUtil.markersInWindow(new double[]{0, 1, 2}, 2, 3, 2, 0).size() != 1) {
            throw new AssertionError("Final endpoint marker was dropped");
        }
        System.out.println("Passed " + checked + " marker partition checks plus final endpoint check.");
    }
}
