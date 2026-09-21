package net.oxcodsnet.roadarchitect.fabric.events;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.oxcodsnet.roadarchitect.api.addon.RoadAddons;
import net.oxcodsnet.roadarchitect.handlers.GooskerRoadBackfill;
import net.oxcodsnet.roadarchitect.handlers.RoadPipelineController;
import net.oxcodsnet.roadarchitect.util.DebugLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoadPipelineFabricEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"roadarchitect/FabricEvents");

    private RoadPipelineFabricEvents() {
    }

    public static void register() {
        RoadPipelineController.init();
        boolean dhPresent = FabricLoader.getInstance().isModLoaded("distanthorizons");
        if (dhPresent) {
            DebugLog.info(LOGGER, "Distant Horizons detected: skipping INIT pregen; pipeline will start on player join", new Object[0]);
        }
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (!dhPresent) {
                RoadPipelineController.onSpawnChunkGenerated(world, (ChunkAccess)chunk);
            }
            GooskerRoadBackfill.onChunkLoadAndPipeline(world, (ChunkAccess)chunk);
            RoadAddons.onChunkLoad(world, chunk.getPos());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> RoadPipelineController.onPlayerJoin(handler.getPlayer()));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            GooskerRoadBackfill.onServerTickAndPipeline(server);
            RoadAddons.onServerTick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GooskerRoadBackfill.onServerStoppingAndPipeline());
    }
}

