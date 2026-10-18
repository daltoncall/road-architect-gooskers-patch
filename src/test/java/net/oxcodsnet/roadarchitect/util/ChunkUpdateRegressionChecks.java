package net.oxcodsnet.roadarchitect.util;

import java.lang.reflect.Proxy;
import java.util.*;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.oxcodsnet.roadarchitect.storage.TreeRemovalQueue;
import net.oxcodsnet.roadarchitect.worldgen.*;

/** Actual overlapping stripe writes and persisted multi-chunk deletions on a counted world. */
public final class ChunkUpdateRegressionChecks {
    private static final class Fixture {
        final Map<BlockPos,BlockState> blocks=new HashMap<>();
        final Set<Long> missing=new HashSet<>(),changedChunks=new HashSet<>();
        int writes;
        final WorldGenLevel world;
        Fixture() {
            Biome biome=new Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8f).downfall(0.4f)
                    .specialEffects(new BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
                    .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build();
            MappedRegistry<Biome> biomes=new MappedRegistry<>(Registries.BIOME,Lifecycle.stable());
            biomes.register(Biomes.PLAINS,biome,Lifecycle.stable());biomes.freeze();
            var access=new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
            world=(WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),new Class<?>[]{WorldGenLevel.class},(proxy,method,args)->switch(method.getName()) {
                case "hasChunk" -> !missing.contains(ChunkPos.asLong((int)args[0],(int)args[1]));
                case "getMinBuildHeight" -> -64;
                case "getMaxBuildHeight" -> 320;
                case "getHeight" -> 384;
                case "getSeed" -> 12345L;
                case "registryAccess" -> access;
                case "getBiome" -> biomes.getHolderOrThrow(Biomes.PLAINS);
                case "getBlockState" -> blocks.getOrDefault(args[0],Blocks.AIR.defaultBlockState());
                case "getFluidState" -> blocks.getOrDefault(args[0],Blocks.AIR.defaultBlockState()).getFluidState();
                case "setBlock" -> { BlockPos pos=((BlockPos)args[0]).immutable();writes++;changedChunks.add(new ChunkPos(pos).toLong());blocks.put(pos,(BlockState)args[1]);yield true; }
                case "removeBlock", "destroyBlock" -> { BlockPos pos=(BlockPos)args[0];writes++;changedChunks.add(new ChunkPos(pos).toLong());blocks.remove(pos);yield true; }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                case "toString" -> "ChunkUpdateFixture";
                default -> throw new AssertionError("Unexpected world access: "+method);
            });
        }
    }
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        Fixture road=new Fixture();
        var generatedContext=new net.minecraft.world.level.levelgen.feature.FeaturePlaceContext<RoadFeatureConfig>(
                Optional.empty(),road.world,null,RandomSource.create(1),new BlockPos(0,64,0),
                new RoadFeatureConfig(3,1,RoadFeatureConfig.GenerationPhase.FINALIZE));
        if(RoadFeatureRegistry.ROAD_FEATURE.place(generatedContext)||road.writes!=0) throw new AssertionError("Legacy feature touched a generation region");
        System.out.println("Legacy road feature rejects a generation region before touching server/chunk state.");
        for(int x=-4;x<=20;x++) for(int z=-4;z<=4;z++) {
            road.blocks.put(new BlockPos(x,63,z),Blocks.DIRT.defaultBlockState());
            road.blocks.put(new BlockPos(x,64,z),Blocks.GRASS_BLOCK.defaultBlockState());
        }
        BlockPos flower=new BlockPos(2,65,0);road.blocks.put(flower,Blocks.DANDELION.defaultBlockState());
        List<BlockPos> path=new ArrayList<>();for(int x=0;x<=15;x++) path.add(new BlockPos(x,64,0));
        for(int i=0;i<path.size();i++) try(var scope=RoadStructureProtection.beginPlacement(road.world)) {
            RoadFeature.buildRoadStripe(road.world,path,i,i+1,1,RandomSource.create(i),RoadFeatureConfig.GenerationPhase.PREPARE);
            RoadFeature.buildRoadStripe(road.world,path,i,i+1,1,RandomSource.create(i),RoadFeatureConfig.GenerationPhase.FINALIZE);
        }
        int first=road.writes;Map<BlockPos,BlockState> completed=Map.copyOf(road.blocks);
        for(int i=0;i<path.size();i++) try(var scope=RoadStructureProtection.beginPlacement(road.world)) {
            RoadFeature.buildRoadStripe(road.world,path,i,i+1,1,RandomSource.create(9000+i),RoadFeatureConfig.GenerationPhase.PREPARE);
            RoadFeature.buildRoadStripe(road.world,path,i,i+1,1,RandomSource.create(9000+i),RoadFeatureConfig.GenerationPhase.FINALIZE);
        }
        System.out.println("Stripe write counts: first="+first+", repeated="+(road.writes-first));
        if(road.writes!=first||!road.blocks.equals(completed)||!road.blocks.get(flower).is(Blocks.DANDELION)) throw new AssertionError("Completed overlapping road cells were repainted or vegetation erased");
        System.out.println("Actual overlapping road stripes: first pass "+first+" writes; repeated pass 0 writes; flowers preserved.");

        Fixture edits=new Fixture();TreeRemovalQueue queue=new TreeRemovalQueue();List<TreeRemovalQueue.Cell> cells=new ArrayList<>();
        for(int i=0;i<384;i++) {
            BlockPos pos=new BlockPos(i%2==0?0:32,65+i/2,0);edits.blocks.put(pos,Blocks.SPRUCE_LOG.defaultBlockState());
            cells.add(new TreeRemovalQueue.Cell(pos,"minecraft:spruce_log",false));
        }
        queue.add(cells);queue.add(cells);
        Map<Long,Integer> lastEdit=new HashMap<>();int tick=0,bursts=0;
        while(queue.size()>0&&tick<100) {
            edits.changedChunks.clear();int removed=queue.process(edits.world,pos->false);tick++;
            if(removed>128||edits.changedChunks.size()>1) throw new AssertionError("Burst exceeded cell/chunk budget");
            for(long key:edits.changedChunks) {
                Integer prior=lastEdit.put(key,tick);if(prior!=null&&tick-prior<7) throw new AssertionError("Chunk changed before six quiet ticks elapsed");
                bursts++;
            }
        }
        if(queue.size()!=0||!edits.blocks.isEmpty()) throw new AssertionError("Coalesced complete removal never finished");
        System.out.println("Interleaved tree cells: "+bursts+" one-chunk bursts; at most 128 candidates; six quiet ticks before reuse; all 384 cells removed.");

        Fixture unloaded=new Fixture();TreeRemovalQueue waiting=new TreeRemovalQueue();
        for(int chunk=0;chunk<10;chunk++) {
            BlockPos pos=new BlockPos(chunk*16,65,0);unloaded.blocks.put(pos,Blocks.SPRUCE_LOG.defaultBlockState());
            waiting.add(List.of(new TreeRemovalQueue.Cell(pos,"minecraft:spruce_log",false)));
            if(chunk<9) unloaded.missing.add(ChunkPos.asLong(chunk,0));
        }
        for(int i=0;i<3;i++) waiting.process(unloaded.world,pos->false);
        if(unloaded.blocks.containsKey(new BlockPos(144,65,0))||waiting.size()!=9) throw new AssertionError("Unloaded chunks starved ready cleanup");
        waiting=TreeRemovalQueue.load(waiting.save(new CompoundTag()));
        if(!waiting.hasPendingAround(new ChunkPos(0,0),2)||waiting.hasPendingAround(new ChunkPos(30,0),2)) throw new AssertionError("Finalization gate has wrong pending footprint");
        unloaded.missing.clear();while(waiting.size()>0) waiting.process(unloaded.world,pos->false);
        if(!unloaded.blocks.isEmpty()) throw new AssertionError("Saved retries did not resume");
        System.out.println("Unloaded chunks rotate fairly; pending-footprint gate and restart retries passed.");
    }
}
