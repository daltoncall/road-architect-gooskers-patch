package net.oxcodsnet.roadarchitect.util;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.ChunkPos;
import net.oxcodsnet.roadarchitect.storage.PlayerPlacedTreeBlocks;

/** Loaded-only, per-tree snapshot of generated structure bounds plus player placement history. */
public final class TreeBuildProtection {
    private TreeBuildProtection() {}
    public static boolean structureInfoMissing(ServerLevel level,BlockPos pos) {
        var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null) return true;
        for(var references:chunk.getAllReferences().values()) for(long packed:references) {
            ChunkPos origin=new ChunkPos(packed);
            if(level.getChunkSource().getChunkNow(origin.x,origin.z)==null) return true;
        }
        return false;
    }
    public static Predicate<BlockPos> snapshot(WorldGenLevel world, BlockPos center) {
        if (!(world instanceof ServerLevel level)) return pos -> false;
        var placed = PlayerPlacedTreeBlocks.get(level);
        List<BoundingBox> boxes = new ArrayList<>();
        Set<Long> uncertain = new HashSet<>();
        Set<Object> startsSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        int cx = center.getX() >> 4, cz = center.getZ() >> 4;
        for (int dx=-2; dx<=2; dx++) for (int dz=-2; dz<=2; dz++) {
            var chunk = level.getChunkSource().getChunkNow(cx+dx,cz+dz);
            if (chunk == null) continue;
            for (var start : chunk.getAllStarts().values()) {
                if (start.isValid() && startsSeen.add(start)) boxes.add(start.getBoundingBox());
            }
            for (var entry : chunk.getAllReferences().entrySet()) {
                for (long packed : entry.getValue()) {
                    ChunkPos origin = new ChunkPos(packed);
                    var owner = level.getChunkSource().getChunkNow(origin.x,origin.z);
                    if (owner == null) { uncertain.add(chunk.getPos().toLong()); continue; }
                    var start = owner.getAllStarts().get(entry.getKey());
                    if (start != null && start.isValid() && startsSeen.add(start)) boxes.add(start.getBoundingBox());
                }
            }
        }
        return pos -> placed.contains(pos)
                || uncertain.contains(ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4))
                || boxes.stream().anyMatch(box -> box.isInside(pos));
    }
}
