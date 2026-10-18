package net.oxcodsnet.roadarchitect.handlers;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.oxcodsnet.roadarchitect.RoadArchitect;
import net.oxcodsnet.roadarchitect.util.DebugLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stability-first structure trigger controller.
 *
 * <p>0.3.13 removes the old player-join/periodic radius scans. A target
 * structure's own completed chunk is the trigger. The chunk callback only
 * queues a tiny request; at most one request is resolved on a later server tick
 * after the surrounding chunks are already completed LevelChunks.</p>
 */
public final class RoadPipelineController {
    private static final Logger LOGGER = LoggerFactory.getLogger(RoadArchitect.MOD_ID + "/" + RoadPipelineController.class.getSimpleName());

    private static final Set<ResourceLocation> TARGET_IDS = new HashSet<>();
    private static final Set<TagKey<Structure>> TARGET_TAGS = new HashSet<>();
    private static final Set<ResourceLocation> TARGET_DIMENSION_IDS = new HashSet<>();

    private static final ConcurrentLinkedQueue<PendingStructureScan> PENDING = new ConcurrentLinkedQueue<>();
    private static final Set<PendingStructureScan> PENDING_SET = ConcurrentHashMap.newKeySet();
    private static final int MAX_PENDING_POLLS_PER_TICK = 4;

    private RoadPipelineController() {
    }

    public static void init() {
        cacheStructureSelectors();
        PENDING.clear();
        PENDING_SET.clear();
        DebugLog.info(LOGGER, "RoadPipelineController initialized (post-load structure triggers)");
    }

    public static void refreshStructureSelectorCache() {
        cacheStructureSelectors();
        DebugLog.info(LOGGER, "RoadPipelineController reloaded selectors from config");
    }

    /** Legacy hook retained for platform compatibility; CHUNK_LOAD handles discovery. */
    public static void onSpawnChunkGenerated(ServerLevel world, ChunkAccess chunk) {
        onChunkGenerated(world, chunk);
    }

    /**
     * CHUNK_LOAD hot path: only inspect this chunk's already-created structure
     * starts and queue a later post-load scan when one matches.
     */
    public static void onChunkGenerated(ServerLevel world, ChunkAccess chunk) {
        if (!isDimensionEnabled(world.dimension())) return;
        if (!containsTargetStructure(world, chunk)) return;

        ChunkPos pos = chunk.getPos();
        PendingStructureScan pending = new PendingStructureScan(world, pos.x, pos.z);
        if (PENDING_SET.add(pending)) {
            PENDING.offer(pending);
            DebugLog.info(LOGGER, "Target structure chunk {} queued for post-load road discovery", pos);
        }
    }

    /** No full scan on login anymore. Loaded chunks discover themselves. */
    public static void onPlayerJoin(ServerPlayer player) {
        // Intentionally empty in 0.3.13.
    }

    /**
     * Processes at most one ready target-structure trigger. This replaces the
     * old every-N-seconds scan around every player.
     */
    public static void onServerTick(MinecraftServer server) {
        for (int i = 0; i < MAX_PENDING_POLLS_PER_TICK; i++) {
            PendingStructureScan pending = PENDING.poll();
            if (pending == null) return;
            PENDING_SET.remove(pending);

            ServerLevel world = pending.world();
            if (world == null || world.getServer() != server) continue;
            if (!isDimensionEnabled(world.dimension())) continue;

            if (!scanAreaReady(world, pending.chunkX(), pending.chunkZ())) {
                // Rotate not-ready structure work instead of allowing the head
                // entry to starve another already-loaded target behind it.
                if (PENDING_SET.add(pending)) PENDING.offer(pending);
                continue;
            }

            // Radius 1 is sufficient because the trigger chunk already proved
            // that a configured target structure starts here.
            PipelineRunner.runPipeline(world,
                    new ChunkPos(pending.chunkX(), pending.chunkZ()).getMiddleBlockPosition(0),
                    PipelineRunner.PipelineMode.CHUNK);
            return;
        }
    }

    public static void onServerStopping() {
        PENDING.clear();
        PENDING_SET.clear();
        DebugLog.info(LOGGER, "Server stopping, post-load structure queue cleared");
    }

    private static boolean scanAreaReady(ServerLevel world, int chunkX, int chunkZ) {
        ServerChunkCache chunks = world.getChunkSource();
        if (chunks == null) return false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (chunks.getChunkNow(chunkX + dx, chunkZ + dz) == null) return false;
            }
        }
        return true;
    }

    private static void cacheStructureSelectors() {
        TARGET_IDS.clear();
        TARGET_TAGS.clear();
        List<String> selectors = RoadArchitect.CONFIG.structureSelectors();
        for (String sel : selectors) {
            if (sel.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(sel.substring(1));
                if (tagId == null) {
                    LOGGER.warn("Skipping invalid structure tag selector '{}'", sel);
                    continue;
                }
                TARGET_TAGS.add(TagKey.create(Registries.STRUCTURE, tagId));
            } else {
                ResourceLocation id = ResourceLocation.tryParse(sel);
                if (id == null) {
                    LOGGER.warn("Skipping invalid structure selector '{}'", sel);
                    continue;
                }
                TARGET_IDS.add(id);
            }
        }

        TARGET_DIMENSION_IDS.clear();
        List<String> dimensionSelectors = RoadArchitect.CONFIG.dimensionSelectors();
        if (dimensionSelectors == null || dimensionSelectors.isEmpty()) {
            TARGET_DIMENSION_IDS.add(Level.OVERWORLD.location());
        } else {
            for (String selector : dimensionSelectors) {
                if (selector.startsWith("#")) {
                    LOGGER.warn("Dimension selector tags are not supported (skipping '{}')", selector);
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(selector);
                if (id == null) {
                    LOGGER.warn("Skipping invalid dimension selector '{}'", selector);
                    continue;
                }
                TARGET_DIMENSION_IDS.add(id);
            }
            if (TARGET_DIMENSION_IDS.isEmpty()) {
                TARGET_DIMENSION_IDS.add(Level.OVERWORLD.location());
            }
        }
    }

    static boolean isDimensionEnabled(ResourceKey<Level> key) {
        if (TARGET_DIMENSION_IDS.isEmpty()) {
            return key == Level.OVERWORLD;
        }
        return TARGET_DIMENSION_IDS.contains(key.location());
    }

    private static boolean containsTargetStructure(ServerLevel world, ChunkAccess chunk) {
        // Structure starts are independent of their reference map.
        if (chunk.getAllStarts().isEmpty()) return false;

        Registry<Structure> registry = world.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (StructureStart start : chunk.getAllStarts().values()) {
            if (!start.isValid()) continue;
            Structure structure = start.getStructure();
            ResourceLocation id = registry.getKey(structure);
            if (id != null && TARGET_IDS.contains(id)) return true;

            Holder<Structure> entry = registry.wrapAsHolder(structure);
            if (entry != null) {
                for (TagKey<Structure> tag : TARGET_TAGS) {
                    if (entry.is(tag)) return true;
                }
            }
        }
        return false;
    }

    private record PendingStructureScan(ServerLevel world, int chunkX, int chunkZ) {
    }
}
