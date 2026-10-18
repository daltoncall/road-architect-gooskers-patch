package net.oxcodsnet.roadarchitect.handlers;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.oxcodsnet.roadarchitect.storage.PathStorage;
import net.oxcodsnet.roadarchitect.util.GooskerRoadVegetation;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;

/** Revisits saved road routes after load. No scans or edits in chunk-generation callbacks. */
public final class AutomaticRoadTreeCleanup {
    private static final ArrayDeque<Job> pending=new ArrayDeque<>();
    private static final Set<Key> queued=new HashSet<>();
    private record Key(ServerLevel world,ChunkPos chunk) {}
    private static final class Job {
        final Key key; List<BlockPos> points; int cursor;
        Job(Key key) { this.key=key; }
    }
    private AutomaticRoadTreeCleanup() {}

    public static void onChunkLoad(ServerLevel world,ChunkPos chunk) {
        if(!PathStorage.get(world).hasRoadPoints(chunk)) return;
        Key key=new Key(world,chunk);
        if(queued.add(key)) pending.add(new Job(key));
    }

    public static void tick(MinecraftServer server) {
        for(int checked=0;checked<8;checked++) {
            Job job=pending.poll(); if(job==null) return;
            ServerLevel world=job.key.world(); ChunkPos chunk=job.key.chunk();
            if(world.getChunkSource().getChunkNow(chunk.x,chunk.z)==null) { queued.remove(job.key);continue; }
            if(job.points==null) job.points=PathStorage.get(world).readyRoadPoints(chunk);
            if(job.cursor>=job.points.size()) { queued.remove(job.key);continue; }
            boolean ready=true;
            for(int dx=-2;dx<=2&&ready;dx++) for(int dz=-2;dz<=2;dz++)
                if(world.getChunkSource().getChunkNow(chunk.x+dx,chunk.z+dz)==null) { ready=false;break; }
            if(!ready) { pending.add(job);continue; }
            BlockPos pos=job.points.get(job.cursor++);
            try(var scope=RoadStructureProtection.beginPlacement(world)) {
                GooskerRoadVegetation.clearForSavedRoad(world,pos,Math.max(0,net.oxcodsnet.roadarchitect.RoadArchitect.CONFIG.roadWidth()/2));
            }
            if(job.cursor<job.points.size()) pending.add(job); else queued.remove(job.key);
            return; // one saved road point per server tick, across all worlds
        }
    }

    public static void clear() { pending.clear();queued.clear(); }
}
