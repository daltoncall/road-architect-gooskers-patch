package net.oxcodsnet.roadarchitect.util;

import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.oxcodsnet.roadarchitect.storage.PlayerPlacedTreeBlocks;
import net.oxcodsnet.roadarchitect.storage.TreeRemovalQueue;

public final class TreeRegressionChecks {
    private static final class Fixture {
        final Map<BlockPos,BlockState> blocks=new HashMap<>();
        final Set<Long> missing=new HashSet<>(); int removals;
        final WorldGenLevel world=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->switch(method.getName()) {
            case "hasChunk" -> !missing.contains(net.minecraft.world.level.ChunkPos.asLong((int)args[0],(int)args[1]));
            case "getMinBuildHeight" -> -64;
            case "getMaxBuildHeight" -> 320;
            case "getHeight" -> 384;
            case "getBlockState" -> blocks.getOrDefault(args[0],Blocks.AIR.defaultBlockState());
            case "getFluidState" -> blocks.getOrDefault(args[0],Blocks.AIR.defaultBlockState()).getFluidState();
            case "removeBlock", "destroyBlock" -> { removals++;blocks.remove(args[0]);yield true; }
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy==args[0];
            case "toString" -> "TreeFixture";
            default -> throw new AssertionError("Unexpected world access: "+method);
        });
        void set(int x,int y,int z,BlockState state) { blocks.put(new BlockPos(x,y,z),state); }
    }
    private static Fixture giant() {
        Fixture f=new Fixture();
        for(int x=0;x<2;x++) for(int z=0;z<2;z++) {
            f.set(x,64,z,Blocks.PODZOL.defaultBlockState());
            for(int y=65;y<=160;y++) f.set(x,y,z,Blocks.SPRUCE_LOG.defaultBlockState());
        }
        for(int x=-6;x<=7;x++) for(int z=-6;z<=7;z++) for(int y=157;y<=165;y++) {
            if(x>=0&&x<2&&z>=0&&z<2&&y<=160) continue;
            f.set(x,y,z,Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,false));
        }
        return f;
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Fixture tall=giant(); GooskerRoadVegetation.clearForRoad(tall.world,new BlockPos(0,64,0));
        if(tall.blocks.values().stream().anyMatch(state->state.is(Blocks.SPRUCE_LOG)||state.is(Blocks.SPRUCE_LEAVES))) throw new AssertionError("96-block giant spruce left wood/canopy");
        System.out.println("96-block 2x2 spruce: all trunk/canopy blocks cleared.");

        Fixture ambiguous=new Fixture();
        for(int y=65;y<=128;y++) ambiguous.set(0,y,0,Blocks.SPRUCE_LOG.defaultBlockState());
        ambiguous.set(2,66,0,Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,false));
        GooskerRoadVegetation.clearForRoad(ambiguous.world,new BlockPos(0,64,0));
        if(ambiguous.removals!=0) throw new AssertionError("Unproved trunk/canopy partially stripped");
        ambiguous.set(0,64,0,Blocks.PODZOL.defaultBlockState());
        GooskerRoadVegetation.clearForRoad(ambiguous.world,new BlockPos(0,64,0));
        if(ambiguous.blocks.values().stream().anyMatch(state->state.is(Blocks.SPRUCE_LOG))) throw new AssertionError("Rooted damaged trunk not cleared automatically");

        Fixture bare=giant(); bare.blocks.entrySet().removeIf(entry->entry.getValue().is(Blocks.SPRUCE_LEAVES));
        GooskerRoadVegetation.clearForRoad(bare.world,new BlockPos(0,64,0));
        if(bare.blocks.values().stream().anyMatch(state->state.is(Blocks.SPRUCE_LOG))) throw new AssertionError("Bare 96-block 2x2 tree needs manual repair");
        Fixture timberFrame=giant(); timberFrame.blocks.entrySet().removeIf(entry->entry.getValue().is(Blocks.SPRUCE_LEAVES));
        timberFrame.set(2,120,0,Blocks.SPRUCE_PLANKS.defaultBlockState());
        GooskerRoadVegetation.clearForRoad(timberFrame.world,new BlockPos(0,64,0));
        if(timberFrame.removals!=0) throw new AssertionError("High building attachment was ignored");
        Fixture horizontal=giant(); horizontal.blocks.entrySet().removeIf(entry->entry.getValue().is(Blocks.SPRUCE_LEAVES));
        horizontal.set(0,100,0,Blocks.SPRUCE_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,net.minecraft.core.Direction.Axis.X));
        if(CompleteTreeRemoval.remove(horizontal.world,new BlockPos(0,65,0),pos->false)!=0) throw new AssertionError("Bare frame with horizontal beam cleared");
        Fixture persistent=giant(); persistent.blocks.entrySet().removeIf(entry->entry.getValue().is(Blocks.SPRUCE_LEAVES));
        persistent.set(2,140,0,Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true));
        if(CompleteTreeRemoval.remove(persistent.world,new BlockPos(0,65,0),pos->false)!=0) throw new AssertionError("Player leaf attachment was ignored");
        System.out.println("Damaged single/giant rooted trunks clear automatically; unsupported poles, high building attachments, horizontal frames and player leaves survive.");

        Fixture roadEdge=new Fixture();
        for(int x:List.of(4,8)) {
            roadEdge.set(x,64,0,Blocks.PODZOL.defaultBlockState());
            for(int y=65;y<=95;y++) roadEdge.set(x,y,0,Blocks.SPRUCE_LOG.defaultBlockState());
        }
        GooskerRoadVegetation.clearForSavedRoad(roadEdge.world,new BlockPos(0,64,0),3);
        if(roadEdge.blocks.containsKey(new BlockPos(4,65,0))||!roadEdge.blocks.containsKey(new BlockPos(8,65,0))) throw new AssertionError("Saved-road width cleanup missed edge trunk or removed distant tree");
        System.out.println("Saved-road width covers damaged edge trunks while distant trees survive.");

        Fixture player=giant(); PlayerPlacedTreeBlocks placed=new PlayerPlacedTreeBlocks();
        player.blocks.forEach((pos,state)-> {if(state.is(Blocks.SPRUCE_LOG)||state.is(Blocks.SPRUCE_LEAVES)) placed.mark(pos);});
        PlayerPlacedTreeBlocks restored=PlayerPlacedTreeBlocks.load(placed.save(new CompoundTag()));
        if(CompleteTreeRemoval.remove(player.world,new BlockPos(0,65,0),restored::contains)!=0 || player.removals!=0) throw new AssertionError("Identical player-built tree shape removed");
        BlockPos known=new BlockPos(0,65,0); restored.forget(known); if(restored.contains(known)) throw new AssertionError("Broken placement stayed protected");
        Fixture generated=giant(); BoundingBox structure=new BoundingBox(-8,64,-8,8,180,8);
        if(CompleteTreeRemoval.remove(generated.world,new BlockPos(0,65,0),structure::isInside)!=0 || generated.removals!=0) throw new AssertionError("Generated structure wood removed");
        Fixture partiallyProtected=giant();
        if(CompleteTreeRemoval.remove(partiallyProtected.world,new BlockPos(0,65,0),new BlockPos(1,120,1)::equals)!=0 || partiallyProtected.removals!=0) throw new AssertionError("Tree attached to protected wood was partly chopped");
        System.out.println("Saved player placement identity and generated structure bounds preserved identical wood shapes.");

        Fixture pair=new Fixture();
        for(int y=65;y<=70;y++) {pair.set(0,y,0,Blocks.OAK_LOG.defaultBlockState());pair.set(4,y,0,Blocks.OAK_LOG.defaultBlockState());}
        for(int x=0;x<=4;x++) for(int z=-1;z<=1;z++) pair.set(x,71,z,Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,false));
        if(CompleteTreeRemoval.remove(pair.world,new BlockPos(0,65,0),pos->false)<=0) throw new AssertionError("Target tree not cleared");
        if(!pair.blocks.get(new BlockPos(4,65,0)).is(Blocks.OAK_LOG)||!pair.blocks.get(new BlockPos(4,71,0)).is(Blocks.OAK_LEAVES)) throw new AssertionError("Neighbor tree stripped");
        if(pair.blocks.containsKey(new BlockPos(0,65,0))) throw new AssertionError("Target trunk survived");

        Fixture border=new Fixture();
        for(int y=65;y<=70;y++) border.set(15,y,0,Blocks.OAK_LOG.defaultBlockState());
        border.missing.add(net.minecraft.world.level.ChunkPos.asLong(1,0));
        if(CompleteTreeRemoval.remove(border.world,new BlockPos(15,65,0),pos->false)!=0||border.removals!=0) throw new AssertionError("Partial deletion crossed unloaded boundary");

        Fixture queued=new Fixture(); TreeRemovalQueue queue=new TreeRemovalQueue(); List<TreeRemovalQueue.Cell> cells=new ArrayList<>();
        for(int x=0;x<300;x++) {BlockPos pos=new BlockPos(x%16,65+x/16,0);queued.blocks.put(pos,Blocks.SPRUCE_LOG.defaultBlockState());cells.add(new TreeRemovalQueue.Cell(pos,"minecraft:spruce_log",false));}
        queue.add(cells);queue.add(cells);
        if(queue.size()!=300||queue.process(queued.world,pos->false)!=128||queue.size()!=172) throw new AssertionError("Removal budget/dedup failed");
        queue=TreeRemovalQueue.load(queue.save(new CompoundTag()));
        BlockPos replacement=new BlockPos(299%16,65+299/16,0), protectedLog=new BlockPos(298%16,65+298/16,0);
        queued.blocks.put(replacement,Blocks.CHEST.defaultBlockState());
        while(queue.size()>0) {int removed=queue.process(queued.world,protectedLog::equals);if(removed>128) throw new AssertionError("Per-tick budget exceeded");}
        if(queued.blocks.size()!=2||!queued.blocks.get(replacement).is(Blocks.CHEST)||!queued.blocks.get(protectedLog).is(Blocks.SPRUCE_LOG)) throw new AssertionError("Queued job removed replacement/player block");
        System.out.println("Neighbor canopy, unloaded-boundary abort, 128-cell budget, dedup, restart, replacement and protection checks passed.");

        var paths=new net.oxcodsnet.roadarchitect.storage.PathStorage();
        var first=new net.minecraft.world.level.ChunkPos(0,0);
        var second=new net.minecraft.world.level.ChunkPos(1,0);
        var a=new BlockPos(0,64,0); var b=new BlockPos(16,64,0);
        paths.updatePath("a|b",List.of(a,b),net.oxcodsnet.roadarchitect.storage.PathStorage.Status.READY);
        paths.updatePath("c|d",List.of(a),net.oxcodsnet.roadarchitect.storage.PathStorage.Status.PENDING);
        if(!paths.hasRoadPoints(first)||!paths.readyRoadPoints(first).equals(List.of(a))||!paths.readyRoadPoints(second).equals(List.of(b))) throw new AssertionError("Road chunk index lost route/status");
        paths.updatePath("a|b",List.of(b),net.oxcodsnet.roadarchitect.storage.PathStorage.Status.READY);
        if(!paths.readyRoadPoints(first).isEmpty()) throw new AssertionError("Old route stayed in cleanup index");
        paths.setStatus("c|d",net.oxcodsnet.roadarchitect.storage.PathStorage.Status.READY);
        var reloaded=net.oxcodsnet.roadarchitect.storage.PathStorage.fromNbt(paths.save(new CompoundTag()));
        if(!reloaded.readyRoadPoints(first).equals(List.of(a))||!reloaded.readyRoadPoints(second).equals(List.of(b))) throw new AssertionError("Existing road cleanup index not restored on restart");
        System.out.println("Existing-road chunk lookup, path replacement, status changes and restart indexing passed.");
    }
}
