package net.oxcodsnet.roadarchitect.util;

import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.oxcodsnet.roadarchitect.handlers.compat.GooskerBeeNestCleanup;
import net.oxcodsnet.roadarchitect.storage.PathStorage;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;

/** Actual helper operations on a counted, loaded-world fixture; not an in-game TPS benchmark. */
public final class PerformanceRegressionChecks {
    private static final class Fixture {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        long reads;
        final WorldGenLevel world = (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "hasChunk", "hasChunkAt" -> true;
                        case "getMinBuildHeight" -> -64;
                        case "getMaxBuildHeight" -> 320;
                        case "getHeight" -> 384;
                        case "getBlockState" -> { reads++; yield blocks.getOrDefault(args[0], Blocks.AIR.defaultBlockState()); }
                        case "getFluidState" -> blocks.getOrDefault(args[0], Blocks.AIR.defaultBlockState()).getFluidState();
                        case "removeBlock", "destroyBlock" -> { blocks.remove(args[0]); yield true; }
                        case "setBlock" -> { blocks.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]); yield true; }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "CountedWorldFixture";
                        default -> throw new AssertionError("Unexpected world access: " + method);
                    };
                });
        void set(int x, int y, int z, BlockState state) { blocks.put(new BlockPos(x,y,z), state); }
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos pos = new BlockPos(0,64,0);
        Fixture plain = new Fixture();
        for (int i=0; i<8; i++) {
            if (RoadStructureProtection.hasNearbyBuildingForFinalize(plain.world, pos)) throw new AssertionError("False building");
        }
        long oldReads = plain.reads;
        plain.reads = 0;
        try (var scope = RoadStructureProtection.beginPlacement(plain.world)) {
            for (int i=0; i<8; i++) {
                if (RoadStructureProtection.hasNearbyBuildingForFinalize(plain.world, pos)) throw new AssertionError("False building");
            }
        }
        long newReads = plain.reads;
        if (newReads * 8 != oldReads) throw new AssertionError("Protection work was not reduced by eight");
        // Closing a scope must discard its false result before new player/building changes.
        plain.set(0,63,0,Blocks.CHEST.defaultBlockState());
        if (!RoadStructureProtection.hasNearbyBuildingForFinalize(plain.world,pos)) throw new AssertionError("Scope leaked");
        try (var scope = RoadStructureProtection.beginPlacement(plain.world)) {
            if (!RoadStructureProtection.hasNearbyBuildingForFinalize(plain.world,pos)) throw new AssertionError("Building not protected");
            Fixture separate = new Fixture();
            if (RoadStructureProtection.hasNearbyBuildingForFinalize(separate.world,pos)) throw new AssertionError("World cache collision");
        }
        System.out.println("Protection fixture: " + oldReads + " -> " + newReads + " block reads (87.5% fewer across eight repeated checks).");

        Fixture vegetation = new Fixture();
        long firstVegetationReads;
        try (var scope = RoadStructureProtection.beginPlacement(vegetation.world)) {
            GooskerRoadVegetation.clearForRoad(vegetation.world, pos);
            firstVegetationReads = vegetation.reads;
            GooskerRoadVegetation.clearForRoad(vegetation.world, pos);
            if (vegetation.reads != firstVegetationReads) throw new AssertionError("Repeated vegetation scan within same point");
        }
        GooskerRoadVegetation.clearForRoad(vegetation.world, pos);
        if (vegetation.reads <= firstVegetationReads) throw new AssertionError("Vegetation scope leaked across points");
        System.out.println("Repeated PREPARE/FINALIZE vegetation scan eliminated within a point; later points recheck.");

        Fixture legacy = new Fixture();
        Fixture targeted = new Fixture();
        BlockPos nest = pos.above(3), unsupported = pos.above(6), supported = pos.offset(2,0,0);
        targeted.blocks.put(nest,Blocks.BEE_NEST.defaultBlockState());
        targeted.blocks.put(unsupported,Blocks.SNOW.defaultBlockState());
        targeted.blocks.put(supported,Blocks.SNOW.defaultBlockState());
        targeted.blocks.put(supported.below(),Blocks.OAK_STAIRS.defaultBlockState());
        BlockPos hive = pos.offset(3,0,0); targeted.blocks.put(hive,Blocks.BEEHIVE.defaultBlockState());
        legacy.blocks.putAll(targeted.blocks);
        LegacyBeeNestScan.afterTreeCleanup(legacy.world, List.of(pos, pos.above(5)));
        long legacyReads = legacy.reads;
        GooskerBeeNestCleanup.cleanupCandidates(targeted.world, List.of(nest,hive), List.of(unsupported,supported));
        if (targeted.blocks.containsKey(nest) || targeted.blocks.containsKey(unsupported)) throw new AssertionError("Debris survived");
        if (!targeted.blocks.containsKey(supported) || !targeted.blocks.containsKey(hive)) throw new AssertionError("Supported snow/beehive removed");
        if (targeted.reads > 6) throw new AssertionError("Unexpected volume scan");
        System.out.println("Bee/snow follow-up fixture: " + legacyReads + " -> " + targeted.reads + " block reads; nests/unsupported snow removed, supported snow/beehives preserved.");

        Fixture tree = new Fixture();
        tree.set(0,64,0,Blocks.GRASS_BLOCK.defaultBlockState());
        for (int y=65;y<=69;y++) tree.set(0,y,0,Blocks.OAK_LOG.defaultBlockState());
        for (int x=-2;x<=2;x++) for (int z=-2;z<=2;z++) tree.set(x,70,z,
                Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,false));
        tree.set(1,67,0,Blocks.BEE_NEST.defaultBlockState());
        tree.set(0,71,0,Blocks.SNOW.defaultBlockState());
        tree.set(2,65,0,Blocks.BEEHIVE.defaultBlockState());
        tree.set(3,65,0,Blocks.DANDELION.defaultBlockState());
        GooskerRoadVegetation.clearForRoad(tree.world,pos);
        if (tree.blocks.values().stream().anyMatch(state -> state.is(Blocks.OAK_LOG) || state.is(Blocks.OAK_LEAVES)
                || state.is(Blocks.BEE_NEST) || state.is(Blocks.SNOW))) throw new AssertionError("Tree or orphan attachment survived");
        if (!tree.blocks.get(new BlockPos(2,65,0)).is(Blocks.BEEHIVE)
                || !tree.blocks.get(new BlockPos(3,65,0)).is(Blocks.DANDELION)) throw new AssertionError("Hive/flower removed");
        Fixture bamboo = new Fixture();
        for (int y=65;y<=80;y++) bamboo.set(0,y,0,Blocks.BAMBOO.defaultBlockState());
        GooskerRoadVegetation.clearForRoad(bamboo.world,pos);
        if (bamboo.blocks.values().stream().anyMatch(state -> state.is(Blocks.BAMBOO))) throw new AssertionError("Bamboo survived");
        System.out.println("Full natural tree, orphan nests/snow, bamboo and flower/beehive preservation checks passed.");

        // A log/leaf structure with persistent leaves must survive whole-tree cleanup.
        Fixture timber = new Fixture();
        for (int y=65;y<=69;y++) timber.set(0,y,0,Blocks.OAK_LOG.defaultBlockState());
        for (int x=-2;x<=2;x++) for (int z=-2;z<=2;z++) timber.set(x,70,z,
                Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true));
        GooskerRoadVegetation.clearForRoad(timber.world,pos);
        if (!timber.blocks.get(pos.above()).is(Blocks.OAK_LOG)) throw new AssertionError("Player timber removed");

        PathStorage paths = new PathStorage();
        for (int i=0;i<10000;i++) paths.updatePath("ready"+i+"|target",List.of(pos),PathStorage.Status.READY);
        if (paths.firstPendingKey()!=null) throw new AssertionError("Ready path treated as pending");
        paths.updatePath("new|target",List.of(pos),PathStorage.Status.PENDING);
        if (!"new|target".equals(paths.firstPendingKey())) throw new AssertionError("Pending path missing");
        if (!paths.tryMarkProcessing("new|target") || paths.firstPendingKey()!=null) throw new AssertionError("Pending index reservation failed");
        paths.setStatus("new|target",PathStorage.Status.PENDING);
        if (!"new|target".equals(paths.firstPendingKey())) throw new AssertionError("Retry not indexed");
        paths.setStatus("new|target",PathStorage.Status.READY);
        if (paths.firstPendingKey()!=null) throw new AssertionError("Completed path still pending");
        paths.setStatus("new|target",PathStorage.Status.PROCESSING);
        PathStorage restored = PathStorage.fromNbt(paths.save(new net.minecraft.nbt.CompoundTag()));
        if (!"new|target".equals(restored.firstPendingKey())) throw new AssertionError("Interrupted path was not recovered");
        var section = new net.minecraft.world.level.chunk.LevelChunkSection(
                new net.minecraft.world.level.chunk.PalettedContainer<>(
                        net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                        Blocks.AIR.defaultBlockState(),
                        net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES), null);
        if (GooskerStructureEndpoint.sectionHasDoor(section)) throw new AssertionError("Air section contains a door");
        section.setBlockState(0,0,0,Blocks.OAK_DOOR.defaultBlockState(),false);
        if (!GooskerStructureEndpoint.sectionHasDoor(section)) throw new AssertionError("Door section skipped");
        var trapdoorSection = new net.minecraft.world.level.chunk.LevelChunkSection(
                new net.minecraft.world.level.chunk.PalettedContainer<>(
                        net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                        Blocks.AIR.defaultBlockState(),
                        net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES), null);
        trapdoorSection.setBlockState(0,0,0,Blocks.OAK_TRAPDOOR.defaultBlockState(),false);
        if (GooskerStructureEndpoint.sectionHasDoor(trapdoorSection)) throw new AssertionError("Trapdoor selected");
        System.out.println("Door-section palette selection and pending/reserve/retry/restart checks passed.");
    }
}
