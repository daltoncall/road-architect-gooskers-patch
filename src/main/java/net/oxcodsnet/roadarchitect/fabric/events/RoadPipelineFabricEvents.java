package net.oxcodsnet.roadarchitect.fabric.events;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.oxcodsnet.roadarchitect.api.addon.RoadAddons;
import net.oxcodsnet.roadarchitect.handlers.GooskerRoadBackfill;
import net.oxcodsnet.roadarchitect.handlers.RoadPipelineController;

/** Fabric lifecycle bridge for the post-generation 0.3.13 pipeline. */
public final class RoadPipelineFabricEvents {
    private RoadPipelineFabricEvents() {
    }

    public static void register() {
        RoadPipelineController.init();

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            // Cheap notification only. No road placement, broad scans, cache
            // snapshots, or forced chunk work is allowed in this callback.
            GooskerRoadBackfill.onChunkLoadAndPipeline(world, (ChunkAccess)chunk);
            RoadAddons.onChunkLoad(world, chunk.getPos());
            net.oxcodsnet.roadarchitect.handlers.AutomaticRoadTreeCleanup.onChunkLoad(world,chunk.getPos());
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            for(var world:server.getAllLevels()) net.oxcodsnet.roadarchitect.storage.TreeRemovalQueue.get(world).process(world);
            GooskerRoadBackfill.onServerTickAndPipeline(server);
            net.oxcodsnet.roadarchitect.handlers.AutomaticRoadTreeCleanup.tick(server);
            RoadAddons.onServerTick(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            net.oxcodsnet.roadarchitect.handlers.AutomaticRoadTreeCleanup.clear();
            GooskerRoadBackfill.onServerStoppingAndPipeline();
        });
    }
}
