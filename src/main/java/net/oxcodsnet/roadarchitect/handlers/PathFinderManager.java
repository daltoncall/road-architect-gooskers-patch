package net.oxcodsnet.roadarchitect.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.oxcodsnet.roadarchitect.storage.EdgeStorage;
import net.oxcodsnet.roadarchitect.storage.PathStorage;
import net.oxcodsnet.roadarchitect.storage.RoadGraphState;
import net.oxcodsnet.roadarchitect.util.DebugLog;
import net.oxcodsnet.roadarchitect.util.GooskerAsyncPathCommitter;
import net.oxcodsnet.roadarchitect.util.GooskerPathMeander;
import net.oxcodsnet.roadarchitect.util.KeyUtil;
import net.oxcodsnet.roadarchitect.util.PathFinder;
import net.oxcodsnet.roadarchitect.util.profiler.PipelineProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PathFinderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"roadarchitect/PathFinderManager");

    public static void computePaths(ServerLevel world, int preFillCacheZone, int maxSteps) {
        PipelineProfiler.increment("pathfinding.invocations");
        PipelineProfiler.recordValue("pathfinding.prefill_zone", preFillCacheZone);
        PipelineProfiler.recordValue("pathfinding.max_steps", maxSteps);
        RoadGraphState graph = RoadGraphState.get(world);
        PathStorage storage = PathStorage.get(world);
        PathFinder finder = new PathFinder(graph.nodes(), world, maxSteps);
        ArrayList futures = new ArrayList();
        int scheduledJobs = 0;
        try (PipelineProfiler.Section preparation = PipelineProfiler.openSection("pathfinding.prepare_jobs");){
            for (Map.Entry<String, EdgeStorage.Status> entry : graph.edges().allWithStatus().entrySet()) {
                if (entry.getValue() != EdgeStorage.Status.NEW) continue;
                String edgeId = entry.getKey();
                String[] nodes = KeyUtil.parseEdgeKey(edgeId);
                if (nodes.length != 2) {
                    DebugLog.info(LOGGER, "Invalid edge id: {}", edgeId);
                    continue;
                }
                String from = nodes[0];
                String to = nodes[1];
                CompletableFuture<?> job = GooskerAsyncPathCommitter.submitOnce(() -> {
                    long start = System.nanoTime();
                    List<BlockPos> path = GooskerPathMeander.findPath(finder, from, to);
                    double ms = (double)(System.nanoTime() - start) / 1000000.0;
                    return new PathJob(edgeId, from, to, path, ms);
                }, world, edgeId);
                futures.add(job);
                ++scheduledJobs;
            }
        }
        PipelineProfiler.recordValue("pathfinding.jobs_scheduled", scheduledJobs);
        GooskerAsyncPathCommitter.scheduleCommit(world, (Object)graph, (Object)storage, futures);
    }

    public static void computePaths(ServerLevel world) {
        PathFinderManager.computePaths(world, 50, 10480);
    }

    public static void computePaths(ServerLevel world, int preFillCacheZone) {
        PathFinderManager.computePaths(world, preFillCacheZone, 10480);
    }

    private record PathJob(String edgeId, String from, String to, List<BlockPos> path, double durationMs) {
    }
}

