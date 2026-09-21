package net.oxcodsnet.roadarchitect.util;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.commands.arguments.ResourceOrTagKeyArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.oxcodsnet.roadarchitect.storage.RoadGraphState;
import net.oxcodsnet.roadarchitect.storage.components.Node;
import net.oxcodsnet.roadarchitect.util.CacheManager;
import net.oxcodsnet.roadarchitect.util.DebugLog;
import net.oxcodsnet.roadarchitect.util.StringSimilarity;
import net.oxcodsnet.roadarchitect.util.profiler.PipelineProfiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StructureLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)("roadarchitect/" + StructureLocator.class.getSimpleName()));
    private static final DynamicCommandExceptionType INVALID_STRUCTURE_EXCEPTION = new DynamicCommandExceptionType(id -> Component.translatable((String)"commands.locate.structure.invalid", (Object[])new Object[]{id}));
    private static final int MAX_RING_CANDIDATES_PER_CELL = 16;
    private static final Map<Registry<Structure>, Map<String, HolderSet<Structure>>> SELECTOR_CACHE = new WeakHashMap<Registry<Structure>, Map<String, HolderSet<Structure>>>();
    private static final Map<MinecraftServer, NegCache> NEGATIVE_CACHE = new WeakHashMap<MinecraftServer, NegCache>();

    private StructureLocator() {
    }

    private static List<HolderSet<Structure>> compileSelectors(Registry<Structure> registry, List<String> selectors) {
        if (selectors == null || selectors.isEmpty()) {
            return List.of();
        }
        Map cache = SELECTOR_CACHE.computeIfAbsent(registry, r -> new HashMap(selectors.size() * 2));
        ResourceOrTagKeyArgument argType = new ResourceOrTagKeyArgument(Registries.STRUCTURE);
        ArrayList<HolderSet<Structure>> compiled = new ArrayList<HolderSet<Structure>>(selectors.size());
        for (String raw : selectors) {
            if (raw == null || raw.isBlank()) continue;
            HolderSet<Structure> list = (HolderSet<Structure>)cache.get(raw);
            if (list == null) {
                try {
                    ResourceOrTagKeyArgument.Result predicate = argType.parse(new StringReader(raw));
                    list = StructureLocator.getStructureList((ResourceOrTagKeyArgument.Result<Structure>)predicate, registry).orElseThrow(() -> INVALID_STRUCTURE_EXCEPTION.create((Object)raw));
                    cache.put(raw, list);
                }
                catch (CommandSyntaxException ex) {
                    String suggestion = StructureLocator.suggestNearestSelector(raw, registry);
                    if (suggestion != null) {
                        LOGGER.warn("Structure selector '{}' is invalid: {} \u2014 did you mean '{}'?", new Object[]{raw, ex.getMessage(), suggestion});
                        continue;
                    }
                    LOGGER.warn("Structure selector '{}' is invalid: {}", (Object)raw, (Object)ex.getMessage());
                    continue;
                }
            }
            compiled.add(list);
        }
        return compiled;
    }

    private static String suggestNearestSelector(String raw, Registry<Structure> registry) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        ArrayList<String> candidates = new ArrayList<String>(registry.size());
        for (ResourceLocation id : registry.keySet()) {
            candidates.add(id.toString());
        }
        return StringSimilarity.bestMatch(raw, candidates, StringSimilarity.defaultThresholdFor(raw));
    }

    public static List<Pair<BlockPos, String>> scanGridAsync(ServerLevel world, BlockPos origin, int overallRadius, int scanRadius, List<String> structureSelectors) {
        return StructureLocator.scanGridAsync(world, origin, overallRadius, scanRadius, structureSelectors, false);
    }

    public static List<Pair<BlockPos, String>> scanGridAsync(ServerLevel world, BlockPos origin, int overallRadius, int scanRadius, List<String> structureSelectors, boolean allowChunkLoads) {
        PipelineProfiler.increment("structure_locator.invocations");
        PipelineProfiler.recordValue("structure_locator.selector_input", structureSelectors.size());
        PipelineProfiler.recordValue("structure_locator.overall_radius", overallRadius);
        PipelineProfiler.recordValue("structure_locator.scan_radius", scanRadius);
        PipelineProfiler.increment("structure_locator.allow_chunk_loads." + (allowChunkLoads ? "enabled" : "disabled"));
        try (PipelineProfiler.Section total = PipelineProfiler.openSection("structure_locator.total");){
            List<Pair<BlockPos, String>> found;
            List planned;
            PlacementIndex index;
            List<HolderSet<Structure>> compiledSelectors;
            Registry registry = world.registryAccess().registryOrThrow(Registries.STRUCTURE);
            try (PipelineProfiler.Section compile = PipelineProfiler.openSection("structure_locator.compile_selectors")) {
                compiledSelectors = StructureLocator.compileSelectors((Registry<Structure>)registry, structureSelectors);
            }
            PipelineProfiler.recordValue("structure_locator.compiled_selector_groups", compiledSelectors.size());
            if (compiledSelectors.isEmpty()) {
                return Collections.emptyList();
            }
            try (PipelineProfiler.Section indexSection = PipelineProfiler.openSection("structure_locator.build_index");){
                index = StructureLocator.buildPlacementIndex(world, compiledSelectors);
            }
            int baseStep = scanRadius * 2 + 1;
            int step = StructureLocator.computeGridStepChunks(index, baseStep);
            int originChunkX = origin.getX() >> 4;
            int originChunkZ = origin.getZ() >> 4;
            ArrayList<Cell> cells = new ArrayList<Cell>();
            for (int dx = -overallRadius; dx <= overallRadius; dx += step) {
                for (int dz = -overallRadius; dz <= overallRadius; dz += step) {
                    int cx = originChunkX + dx;
                    int cz = originChunkZ + dz;
                    BlockPos cellCenter = new BlockPos((cx << 4) + 8, origin.getY(), (cz << 4) + 8);
                    cells.add(new Cell(cx, cz, cellCenter));
                }
            }
            PipelineProfiler.recordValue("structure_locator.grid_cells", cells.size());
            try (PipelineProfiler.Section planning = PipelineProfiler.openSection("structure_locator.plan_candidates");){
                planned = (List)((ForkJoinTask)ForkJoinPool.commonPool().submit(() -> cells.parallelStream().flatMap(cell -> StructureLocator.planCandidatesForCell(index, cell, scanRadius)).collect(Collectors.toCollection(ArrayList::new)))).join();
            }
            PipelineProfiler.recordValue("structure_locator.planned_candidates", planned.size());
            try (PipelineProfiler.Section resolve = PipelineProfiler.openSection("structure_locator.resolve_candidates");){
                found = StructureLocator.resolveCandidatesOnMainThread(world, (Registry<Structure>)registry, index, planned, allowChunkLoads);
            }
            PipelineProfiler.recordValue("structure_locator.resolved_structures", found.size());
            StructureLocator.schedulePersistence(world, found);
            List<Pair<BlockPos, String>> list = found;
            return list;
        }
    }

    private static PlacementIndex buildPlacementIndex(ServerLevel world, List<HolderSet<Structure>> compiledSelectors) {
        ChunkGeneratorStructureState calc = world.getChunkSource().getGeneratorState();
        HashMap<RandomSpreadStructurePlacement, Set<Holder<Structure>>> randomGroups = new HashMap<RandomSpreadStructurePlacement, Set<Holder<Structure>>>();
        HashMap<ConcentricRingsStructurePlacement, Set<Holder<Structure>>> ringGroups = new HashMap<ConcentricRingsStructurePlacement, Set<Holder<Structure>>>();
        HashMap<ConcentricRingsStructurePlacement, List<ChunkPos>> ringPositions = new HashMap<ConcentricRingsStructurePlacement, List<ChunkPos>>();
        for (HolderSet<Structure> list : compiledSelectors) {
            for (Holder<Structure> entry : list) {
                for (StructurePlacement p : calc.getPlacementsForStructure(entry)) {
                    if (p instanceof RandomSpreadStructurePlacement) {
                        RandomSpreadStructurePlacement rsp = (RandomSpreadStructurePlacement)p;
                        randomGroups.computeIfAbsent(rsp, __ -> new HashSet()).add(entry);
                        continue;
                    }
                    if (!(p instanceof ConcentricRingsStructurePlacement)) continue;
                    ConcentricRingsStructurePlacement cr = (ConcentricRingsStructurePlacement)p;
                    ringGroups.computeIfAbsent(cr, __ -> new HashSet()).add(entry);
                }
            }
        }
        for (ConcentricRingsStructurePlacement cr : ringGroups.keySet()) {
            List positions = calc.getRingPositionsFor(cr);
            if (positions == null) {
                throw new IllegalStateException("Missing ring placement positions");
            }
            ringPositions.put(cr, positions);
        }
        return new PlacementIndex(randomGroups, ringGroups, ringPositions, calc.getLevelSeed());
    }

    private static Stream<Candidate> planCandidatesForCell(PlacementIndex index, Cell cell, int radius) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        for (Map.Entry<RandomSpreadStructurePlacement, Set<Holder<Structure>>> entry : index.randomGroups.entrySet()) {
            RandomSpreadStructurePlacement placement = entry.getKey();
            int spacing = placement.spacing();
            for (int k = 0; k <= radius; ++k) {
                for (int dj = -k; dj <= k; ++dj) {
                    boolean edgeZ = dj == -k || dj == k;
                    for (int di = -k; di <= k; ++di) {
                        boolean edgeX;
                        boolean bl = edgeX = di == -k || di == k;
                        if (!edgeX && !edgeZ) continue;
                        int l = cell.centerChunkX + spacing * di;
                        int m = cell.centerChunkZ + spacing * dj;
                        ChunkPos start = placement.getPotentialStructureChunk(index.structureSeed, l, m);
                        int px = start.getMinBlockX() + 8;
                        int pz = start.getMinBlockZ() + 8;
                        int dist2 = StructureLocator.sq(px - cell.worldCenter.getX()) + StructureLocator.sq(pz - cell.worldCenter.getZ());
                        out.add(new Candidate(start, dist2, (StructurePlacement)placement, entry.getValue()));
                    }
                }
            }
        }
        for (Map.Entry<ConcentricRingsStructurePlacement, Set<Holder<Structure>>> entry : index.ringGroups.entrySet()) {
            ConcentricRingsStructurePlacement placement = entry.getKey();
            List<ChunkPos> positions = index.ringPositions.get(placement);
            if (positions == null || positions.isEmpty()) continue;
            positions.stream().map(cp -> {
                int px = SectionPos.sectionToBlockCoord((int)cp.x, (int)8);
                int pz = SectionPos.sectionToBlockCoord((int)cp.z, (int)8);
                int d2 = StructureLocator.sq(px - cell.worldCenter.getX()) + StructureLocator.sq(pz - cell.worldCenter.getZ());
                return new Object[]{cp, d2};
            }).sorted(Comparator.comparingInt(o -> (Integer)o[1])).limit(16L).forEach(arg_0 -> StructureLocator.lambda$planCandidatesForCell$9(out, (ConcentricRingsStructurePlacement)placement, entry, arg_0));
        }
        out.sort(Comparator.comparingInt(Candidate::prioritySq));
        return out.stream();
    }

    private static int sq(int v) {
        return v * v;
    }

    private static List<Pair<BlockPos, String>> resolveCandidatesOnMainThread(ServerLevel world, Registry<Structure> registry, PlacementIndex index, List<Candidate> candidates, boolean allowChunkLoads) {
        LongOpenHashSet seenXZ = new LongOpenHashSet();
        ArrayList<Pair<BlockPos, String>> found = new ArrayList<Pair<BlockPos, String>>(Math.min(128, candidates.size()));
        LongOpenHashSet neg = StructureLocator.getOrCreateNegativeCache(world);
        StructureManager accessor = world.structureManager();
        PipelineProfiler.recordValue("structure_locator.resolve_candidates_input", candidates.size());
        for (Candidate c : candidates) {
            PipelineProfiler.increment("structure_locator.candidate_examined");
            long negKey = StructureLocator.packNegKey(c.pos, c.placement);
            if (neg.contains(negKey)) {
                PipelineProfiler.increment("structure_locator.negative_cache_hit");
                continue;
            }
            boolean needChunk = false;
            PipelineProfiler.recordValue("structure_locator.structures_per_candidate", c.structs.size());
            for (Holder<Structure> s : c.structs) {
                StructureCheckResult presence;
                PipelineProfiler.increment("structure_locator.presence_checks");
                try (PipelineProfiler.Section presenceTimer = PipelineProfiler.openSection("structure_locator.presence_check");){
                    presence = accessor.checkStructurePresence(c.pos, (Structure)s.value(), true);
                }
                if (presence == StructureCheckResult.START_PRESENT) {
                    PipelineProfiler.increment("structure_locator.presence_positive");
                    BlockPos hit = c.placement.getLocatePos(c.pos);
                    long keyXZ = BlockPos.asLong((int)hit.getX(), (int)0, (int)hit.getZ());
                    if (seenXZ.add(keyXZ)) {
                        ResourceLocation id = registry.getKey(s.value());
                        found.add(Pair.of(new BlockPos(hit.getX(), CacheManager.getHeight(world, hit.getX(), hit.getZ()), hit.getZ()), id == null ? "unknown" : id.toString()));
                        PipelineProfiler.increment("structure_locator.found_structures");
                    }
                    needChunk = false;
                    continue;
                }
                if (presence == StructureCheckResult.START_NOT_PRESENT) continue;
                needChunk = true;
                PipelineProfiler.increment("structure_locator.chunk_confirmation_needed");
            }
            if (!needChunk) {
                neg.add(negKey);
                PipelineProfiler.increment("structure_locator.negative_cache_store");
                continue;
            }
            DebugLog.info(LOGGER, "Skipping chunk load for candidate {} due to allowChunkLoads=false", c.pos);
            PipelineProfiler.increment("structure_locator.chunk_loads_skipped");
        }
        return found;
    }

    private static LongOpenHashSet getOrCreateNegativeCache(ServerLevel world) {
        MinecraftServer server = world.getServer();
        long seed = world.getSeed();
        NegCache nc = NEGATIVE_CACHE.get(server);
        if (nc == null || nc.seed != seed) {
            nc = new NegCache(seed);
            NEGATIVE_CACHE.put(server, nc);
        }
        return nc.set;
    }

    private static long packNegKey(ChunkPos pos, StructurePlacement placement) {
        long a = ChunkPos.asLong((int)pos.x, (int)pos.z);
        int b = System.identityHashCode(placement);
        return a ^ (long)b << 1;
    }

    private static void schedulePersistence(ServerLevel world, List<Pair<BlockPos, String>> found) {
        if (found.isEmpty()) {
            return;
        }
        MinecraftServer server = world.getServer();
        server.execute(() -> {
            RoadGraphState graph = RoadGraphState.get(world);
            for (Pair pair : found) {
                Node node = graph.addNodeWithEdges((BlockPos)pair.getFirst(), (String)pair.getSecond());
                DebugLog.info(LOGGER, "Added node {} at {} ({})", node.id(), node.pos(), pair.getSecond());
            }
            graph.setDirty();
        });
    }

    private static Optional<? extends HolderSet<Structure>> getStructureList(ResourceOrTagKeyArgument.Result<Structure> predicate, Registry<Structure> registry) {
        return (Optional)predicate.unwrap().map(key -> registry.getHolder(key).map(xva$0 -> HolderSet.direct((Holder[])new Holder[]{xva$0})), arg_0 -> registry.getTag(arg_0));
    }

    private static int computeGridStepChunks(PlacementIndex index, int fallbackStep) {
        int minSpacing = Integer.MAX_VALUE;
        for (RandomSpreadStructurePlacement rsp : index.randomGroups.keySet()) {
            minSpacing = Math.min(minSpacing, rsp.spacing());
        }
        if (minSpacing != Integer.MAX_VALUE) {
            int step = Math.max(fallbackStep, minSpacing);
            DebugLog.info(LOGGER, "Grid step optimization: fallbackStep={}, minSpacing={}, chosenStep={}", fallbackStep, minSpacing, step);
            return step;
        }
        return fallbackStep;
    }

    private static /* synthetic */ void lambda$planCandidatesForCell$9(List out, ConcentricRingsStructurePlacement placement, Map.Entry e, Object[] o) {
        out.add(new Candidate((ChunkPos)o[0], (Integer)o[1], (StructurePlacement)placement, (Set)e.getValue()));
    }

    private record PlacementIndex(Map<RandomSpreadStructurePlacement, Set<Holder<Structure>>> randomGroups, Map<ConcentricRingsStructurePlacement, Set<Holder<Structure>>> ringGroups, Map<ConcentricRingsStructurePlacement, List<ChunkPos>> ringPositions, long structureSeed) {
    }

    private record Cell(int centerChunkX, int centerChunkZ, BlockPos worldCenter) {
    }

    private record Candidate(ChunkPos pos, int prioritySq, StructurePlacement placement, Set<Holder<Structure>> structs) {
    }

    private static final class NegCache {
        final long seed;
        final LongOpenHashSet set = new LongOpenHashSet();

        NegCache(long seed) {
            this.seed = seed;
        }
    }
}
