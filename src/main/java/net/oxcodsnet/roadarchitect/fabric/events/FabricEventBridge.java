package net.oxcodsnet.roadarchitect.fabric.events;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.oxcodsnet.roadarchitect.handlers.RoadGraphStateManager;
import net.oxcodsnet.roadarchitect.handlers.RoadPostProcessor;
import net.oxcodsnet.roadarchitect.util.CacheManager;
import net.oxcodsnet.roadarchitect.util.GooskerRoadVegetation;

public final class FabricEventBridge {

    private FabricEventBridge() {}

    public static void register() {
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((world,player,pos,state,entity) -> {
            if(world instanceof net.minecraft.server.level.ServerLevel level)
                net.oxcodsnet.roadarchitect.storage.PlayerPlacedTreeBlocks.get(level).forget(pos);
        });
        ServerWorldEvents.LOAD.register((server,world) ->
                {
                    net.oxcodsnet.roadarchitect.storage.PlayerPlacedTreeBlocks.get(world);
                    net.oxcodsnet.roadarchitect.storage.TreeRemovalQueue.get(world);
                });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> GooskerRoadVegetation.clearClassificationCache());
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) ->
                GooskerRoadVegetation.clearClassificationCache());

        // ===================== RoadGraphStateManager =====================
        ServerWorldEvents.LOAD.register((server, world) -> RoadGraphStateManager.onWorldLoad(world));
        ServerWorldEvents.UNLOAD.register((server, world) -> RoadGraphStateManager.onWorldUnload(world));
        ServerLifecycleEvents.SERVER_STOPPING.register(RoadGraphStateManager::onServerStopping);

        // ===================== PostProcessor =====================
        ServerTickEvents.START_WORLD_TICK.register(RoadPostProcessor::onStartWorldTick);

        // ===================== CacheManager =====================
        ServerWorldEvents.LOAD.register((server, world) -> CacheManager.onWorldLoad(world));
        ServerWorldEvents.UNLOAD.register((server, world) -> CacheManager.onWorldUnload(world));
        ServerLifecycleEvents.SERVER_STOPPING.register(CacheManager::onServerStopping);

        // 0.3.13: no cache work on CHUNK_LOAD/UNLOAD. Distant Horizons can
        // generate/load thousands of chunks; Road Architect must not attach
        // 256-column snapshot work to that hot path. Height data is lazy.
    }
}
