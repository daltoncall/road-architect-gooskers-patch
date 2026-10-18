package net.oxcodsnet.roadarchitect.util;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;
import net.oxcodsnet.roadarchitect.handlers.compat.GooskerBeeNestCleanup;
import net.oxcodsnet.roadarchitect.storage.TreeRemovalQueue;

/** Read-only collection first, then complete bounded removal. Never strips an unproved canopy. */
public final class CompleteTreeRemoval {
    private static final int RADIUS=16, HEIGHT=128, LOG_LIMIT=4096, LEAF_LIMIT=16384;
    private CompleteTreeRemoval() {}

    public static int remove(WorldGenLevel world, BlockPos base, Predicate<BlockPos> protectedPos) {
        if (protectedPos.test(base)) return 0;
        if(world instanceof ServerLevel level && TreeRemovalQueue.get(level).contains(base)) return 0;
        Set<BlockPos> logs = new HashSet<>();
        Set<BlockPos> inspected = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(base);
        while (!queue.isEmpty()) {
            BlockPos pos=queue.poll();
            if (!inspected.add(pos)) continue;
            BlockState state=read(world,pos);
            if (state==null) return 0;
            // A tree connected to protected timber must not be partially chopped.
            if (naturalLog(state) && protectedPos.test(pos)) return 0;
            if (pos.getY()>=base.getY() && buildingEvidence(state)) return 0;
            if (protectedPos.test(pos)) continue;
            if (!inBounds(base,pos)) { if(naturalLog(state)) return 0; continue; } // no partial deletion at an unloaded boundary
            if (!naturalLog(state)) continue;
            logs.add(pos);
            if (logs.size()>LOG_LIMIT) return 0;
            for (int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++) for(int dz=-1;dz<=1;dz++) {
                if(dx!=0||dy!=0||dz!=0) queue.add(pos.offset(dx,dy,dz));
            }
        }
        if(logs.size()<2) return 0;
        Set<BlockPos> leaves=new HashSet<>(), foreignLogs=new HashSet<>(), nests=new HashSet<>(), snow=new HashSet<>(), attachments=new HashSet<>();
        inspected.clear();
        for(BlockPos log:logs) for(Direction dir:Direction.values()) queue.add(log.relative(dir));
        while(!queue.isEmpty()) {
            BlockPos pos=queue.poll();
            if(!inspected.add(pos)) continue;
            BlockState state=read(world,pos);
            if(state==null) return 0;
            if(!inBounds(base,pos)) {
                if(!protectedPos.test(pos) && (naturalLog(state)||naturalLeaf(state))) return 0;
                continue;
            }
            if(naturalLog(state) && !logs.contains(pos)) { foreignLogs.add(pos); continue; }
            if(state.is(Blocks.BEE_NEST)) { if(!protectedPos.test(pos)) nests.add(pos); continue; }
            if(state.is(Blocks.SNOW)) { snow.add(pos); continue; }
            if(protectedPos.test(pos)) continue;
            String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            boolean leaf=naturalLeaf(state);
            boolean attachment=id.contains("vine")||id.contains("cocoa")||id.contains("propagule");
            if(!leaf && !attachment) continue;
            if(leaf) leaves.add(pos); else attachments.add(pos);
            if(leaves.size()+attachments.size()>LEAF_LIMIT) return 0;
            for(Direction dir:Direction.values()) queue.add(pos.relative(dir));
        }
        // Old releases removed the leaves first. Recognize their remaining rooted
        // vertical trunk automatically; no player declaration/command is required.
        if(leaves.size()<9 && !damagedTrunk(world,base,logs)) return 0;
        Map<BlockPos,Integer> ownDistance=distances(logs,leaves);
        Map<BlockPos,Integer> otherDistance=distances(foreignLogs,leaves);
        Set<BlockPos> removedLeaves=new HashSet<>();
        for(BlockPos leaf:leaves) {
            int own=ownDistance.getOrDefault(leaf,Integer.MAX_VALUE);
            int other=otherDistance.getOrDefault(leaf,Integer.MAX_VALUE);
            if(own<other) removedLeaves.add(leaf); // ties preserve neighboring tree canopy
        }
        Set<BlockPos> removedAttachments=new HashSet<>();
        for(BlockPos attachment:attachments) {
            boolean adjacent=Arrays.stream(Direction.values()).anyMatch(dir -> logs.contains(attachment.relative(dir))||removedLeaves.contains(attachment.relative(dir)));
            if(adjacent) removedAttachments.add(attachment);
        }
        for(BlockPos log:logs) snow.add(log.above());
        for(BlockPos leaf:removedLeaves) snow.add(leaf.above());
        if(world instanceof ServerLevel level) {
            List<TreeRemovalQueue.Cell> cells=new ArrayList<>();
            for(Set<BlockPos> group:List.of(logs,removedLeaves,removedAttachments,nests)) for(BlockPos pos:group) {
                BlockState state=read(world,pos);
                if(state!=null && !protectedPos.test(pos)) cells.add(new TreeRemovalQueue.Cell(pos,BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),false));
            }
            for(BlockPos pos:snow) {
                BlockState state=read(world,pos);
                if(state!=null && state.is(Blocks.SNOW)) cells.add(new TreeRemovalQueue.Cell(pos,"minecraft:snow",true));
            }
            TreeRemovalQueue.get(level).add(cells);
            return cells.size();
        }
        int count=0;
        for(BlockPos log:logs) { world.removeBlock(log,false); snow.add(log.above()); count++; }
        for(BlockPos leaf:removedLeaves) { world.removeBlock(leaf,false); snow.add(leaf.above()); count++; }
        for(BlockPos attachment:attachments) {
            boolean adjacent=Arrays.stream(Direction.values()).anyMatch(dir -> logs.contains(attachment.relative(dir))||removedLeaves.contains(attachment.relative(dir)));
            if(adjacent) { world.removeBlock(attachment,false); count++; }
        }
        GooskerBeeNestCleanup.cleanupCandidates(world,nests,snow);
        return count;
    }
    private static Map<BlockPos,Integer> distances(Set<BlockPos> logs,Set<BlockPos> leaves) {
        Map<BlockPos,Integer> result=new HashMap<>(); ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        for(BlockPos log:logs) for(Direction dir:Direction.values()) {
            BlockPos next=log.relative(dir);
            if(leaves.contains(next) && result.putIfAbsent(next,1)==null) queue.add(next);
        }
        while(!queue.isEmpty()) {
            BlockPos pos=queue.poll(); int distance=result.get(pos)+1;
            for(Direction dir:Direction.values()) {
                BlockPos next=pos.relative(dir);
                if(leaves.contains(next) && result.putIfAbsent(next,distance)==null) queue.add(next);
            }
        }
        return result;
    }
    private static boolean inBounds(BlockPos base,BlockPos pos) {
        return Math.abs(pos.getX()-base.getX())<=RADIUS && Math.abs(pos.getZ()-base.getZ())<=RADIUS
                && pos.getY()>=base.getY()-2 && pos.getY()<=base.getY()+HEIGHT;
    }
    private static boolean buildingEvidence(BlockState state) {
        if(state==null||state.isAir()||state.is(Blocks.BEE_NEST)) return false;
        if(state.hasProperty(LeavesBlock.PERSISTENT)&&state.getValue(LeavesBlock.PERSISTENT)) return true;
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return id.contains("stripped_") || RoadStructureProtection.isStrongBuildingBlock(state)
                || RoadStructureProtection.isDistinctiveBuildingBlock(state);
    }
    private static boolean damagedTrunk(WorldGenLevel world,BlockPos base,Set<BlockPos> logs) {
        int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        Map<Long,TreeSet<Integer>> columns=new HashMap<>();
        BlockState trunk=read(world,base);
        if(trunk==null) return false;
        String id=BuiltInRegistries.BLOCK.getKey(trunk.getBlock()).getPath();
        if(!id.endsWith("_log")) return false;
        for(BlockPos pos:logs) {
            BlockState state=read(world,pos);
            if(state==null||state.getBlock()!=trunk.getBlock()||!state.hasProperty(RotatedPillarBlock.AXIS)
                    ||state.getValue(RotatedPillarBlock.AXIS)!=Direction.Axis.Y) return false;
            minX=Math.min(minX,pos.getX());maxX=Math.max(maxX,pos.getX());
            minZ=Math.min(minZ,pos.getZ());maxZ=Math.max(maxZ,pos.getZ());
            columns.computeIfAbsent(net.minecraft.world.level.ChunkPos.asLong(pos.getX(),pos.getZ()),ignored->new TreeSet<>()).add(pos.getY());
        }
        if(maxX-minX>1||maxZ-minZ>1 || (columns.size()!=1 && columns.size()!=4)) return false;
        for(var entry:columns.entrySet()) {
            TreeSet<Integer> heights=entry.getValue();
            if(heights.size()<5||heights.last()-heights.first()+1!=heights.size()) return false;
            var column=new net.minecraft.world.level.ChunkPos(entry.getKey());
            BlockState ground=read(world,new BlockPos(column.x,heights.first()-1,column.z));
            if(ground==null||!(ground.is(BlockTags.DIRT)||ground.is(Blocks.GRASS_BLOCK)||ground.is(Blocks.PODZOL)
                    ||ground.is(Blocks.DIRT)||ground.is(Blocks.COARSE_DIRT)||ground.is(Blocks.ROOTED_DIRT)
                    ||ground.is(Blocks.DIRT_PATH)||ground.is(Blocks.MOSS_BLOCK)||ground.is(Blocks.MUD))) return false;
        }
        return true;
    }
    public static boolean naturalLog(BlockState state) {
        if(state==null||state.isAir()) return false;
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return !id.contains("stripped_") && (state.is(BlockTags.LOGS)||id.endsWith("_log")||id.endsWith("_wood")
                ||id.endsWith("_stem")||id.endsWith("_hyphae")||id.contains("mangrove_roots"));
    }
    private static boolean naturalLeaf(BlockState state) {
        if(state==null||state.isAir()) return false;
        boolean leaf=state.getBlock() instanceof LeavesBlock||state.is(BlockTags.LEAVES)
                ||BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("leaves");
        return leaf && !(state.hasProperty(LeavesBlock.PERSISTENT)&&state.getValue(LeavesBlock.PERSISTENT));
    }
    private static BlockState read(WorldGenLevel world,BlockPos pos) {
        if(world instanceof ServerLevel level) {
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            return chunk==null?null:chunk.getBlockState(pos);
        }
        return world.hasChunk(pos.getX()>>4,pos.getZ()>>4)?world.getBlockState(pos):null;
    }
}
