package net.oxcodsnet.roadarchitect.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.oxcodsnet.roadarchitect.worldgen.RoadStructureProtection;

/**
 * Resolves structure road approach points without adding hard dependencies on
 * the structure mods themselves.
 */
public final class GooskerStructureEndpoint {
    private static final String VALLEY_LODGE = "terralith:valley_lodge";
    private static final int GENERIC_SCAN_RADIUS = 24;
    private static final int GENERIC_SCAN_DOWN = 10;
    private static final int GENERIC_SCAN_UP = 16;
    private static final int VALLEY_SCAN_RADIUS = 20;
    private static final int VALLEY_SCAN_DOWN = 8;
    private static final int VALLEY_SCAN_UP = 12;
    private static final int APPROACH_CLEARANCE = 4;
    private static final int OUTSIDE_PROBE = 6;
    private static final Map<BlockState, Boolean> DOOR_STATES = new ConcurrentHashMap<>();

    private GooskerStructureEndpoint() {}

    /**
     * Standalone structures are moved from their generic locate position to a
     * safe grid cell just outside a likely ground-floor entrance. Multi-building
     * complexes keep the original anchor so one random house door cannot hijack
     * an entire village/fort road node.
     */
    public static BlockPos resolveDiscovered(BlockPos original, ServerLevel world, String type) {
        if (original == null || world == null || type == null) {
            return original;
        }

        try {
            if (VALLEY_LODGE.equals(type)) {
                if (!scanAreaReady(world, original, VALLEY_SCAN_RADIUS + OUTSIDE_PROBE + 1)) {
                    return null; // defer until the whole entrance scan area is already loaded
                }
                BlockPos lodge = resolveValleyLodge(original, world);
                return lodge == null ? original : lodge;
            }

            if (isLikelyCompound(type)) {
                return original;
            }

            if (!scanAreaReady(world, original, GENERIC_SCAN_RADIUS + OUTSIDE_PROBE + 1)) {
                return null; // never synchronously pull neighboring chunks into a chunk-load callback
            }

            BlockPos generic = resolveGenericEntrance(original, world);
            return generic == null ? original : generic;
        } catch (Throwable ignored) {
            // Endpoint refinement is optional. Any unexpected failure falls back
            // to the normal structure anchor instead of breaking the pipeline.
            return original;
        }
    }

    /** Preserve the known-good Valley Lodge behavior from alpha 0.2.14. */
    private static BlockPos resolveValleyLodge(BlockPos original, ServerLevel world) {
        final int ox = original.getX();
        final int oy = original.getY();
        final int oz = original.getZ();
        Map<LevelChunkSection, Boolean> sectionDoors = new IdentityHashMap<>();

        for (int y = oy - VALLEY_SCAN_DOWN; y <= oy + VALLEY_SCAN_UP; y++) {
            DoorCandidate best = null;
            int bestDistSq = Integer.MAX_VALUE;
            for (int dx = -VALLEY_SCAN_RADIUS; dx <= VALLEY_SCAN_RADIUS; dx++) {
                for (int dz = -VALLEY_SCAN_RADIUS; dz <= VALLEY_SCAN_RADIUS; dz++) {
                    int x = ox + dx, z = oz + dz;
                    var chunk = world.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) return null;
                    int sectionIndex = chunk.getSectionIndex(y);
                    if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) continue;
                    var section = chunk.getSections()[sectionIndex];
                    if (!sectionDoors.computeIfAbsent(section, GooskerStructureEndpoint::sectionHasDoor)) continue;
                    BlockPos scan = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(scan);
                    if (state == null) {
                        return null;
                    }
                    String text = lower(state);
                    if (!text.contains("minecraft:spruce_door") || !text.contains("half=lower")) {
                        continue;
                    }
                    int distSq = dx * dx + dz * dz;
                    if (distSq < bestDistSq) {
                        Direction2D direction = parsedFacing(text);
                        if (direction != null) {
                            Direction2D outside = chooseExteriorDirection(world, scan, direction);
                            if (outside == null) {
                                return null;
                            }
                            bestDistSq = distSq;
                            best = new DoorCandidate(scan, outside, 0);
                        }
                    }
                }
            }
            if (best != null) {
                return approachOutside(best.pos(), best.outside());
            }
        }
        return null;
    }

    /**
     * Generic standalone-building entrance resolver. It searches only loaded
     * blocks, limits candidates to the lowest couple of door levels, and picks
     * the side of each door that looks most like exterior terrain/open air.
     */
    private static BlockPos resolveGenericEntrance(BlockPos original, ServerLevel world) {
        List<DoorCandidate> doors = new ArrayList<>();
        int minDoorY = Integer.MAX_VALUE;
        int ox = original.getX();
        int oy = original.getY();
        int oz = original.getZ();
        Map<LevelChunkSection, Boolean> sectionDoors = new IdentityHashMap<>();

        for (int dx = -GENERIC_SCAN_RADIUS; dx <= GENERIC_SCAN_RADIUS; dx++) {
            for (int dz = -GENERIC_SCAN_RADIUS; dz <= GENERIC_SCAN_RADIUS; dz++) {
                int x = ox + dx;
                int z = oz + dz;
                for (int y = oy - GENERIC_SCAN_DOWN; y <= oy + GENERIC_SCAN_UP; y++) {
                    var chunk = world.getChunkSource().getChunkNow(x >> 4, z >> 4);
                    if (chunk == null) return null;
                    int sectionIndex = chunk.getSectionIndex(y);
                    if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) continue;
                    var section = chunk.getSections()[sectionIndex];
                    if (!sectionDoors.computeIfAbsent(section, GooskerStructureEndpoint::sectionHasDoor)) {
                        // Jump over the rest of this vertical section for this column.
                        y = Math.min(oy + GENERIC_SCAN_UP, (y & ~15) + 15);
                        continue;
                    }
                    BlockPos scan = new BlockPos(x, y, z);
                    BlockState state = chunk.getBlockState(scan);
                    if (state == null) {
                        return null;
                    }
                    String text = lower(state);
                    if (!isLowerDoor(text)) {
                        continue;
                    }
                    Direction2D facing = parsedFacing(text);
                    if (facing == null) {
                        continue;
                    }
                    Direction2D outside = chooseExteriorDirection(world, scan, facing);
                    if (outside == null) {
                        return null;
                    }
                    int openness = exteriorScore(world, scan, outside);
                    if (openness == Integer.MIN_VALUE) {
                        return null;
                    }
                    doors.add(new DoorCandidate(scan, outside, openness));
                    minDoorY = Math.min(minDoorY, y);
                }
            }
        }

        if (doors.isEmpty()) {
            return null;
        }

        DoorCandidate best = null;
        long bestScore = Long.MIN_VALUE;
        for (DoorCandidate door : doors) {
            // Ignore obvious upstairs/balcony doors when a lower entrance exists.
            if (door.pos().getY() > minDoorY + 2) {
                continue;
            }
            int dx = door.pos().getX() - ox;
            int dz = door.pos().getZ() - oz;
            int distSq = dx * dx + dz * dz;
            long score = (long)door.openness() * 1000L - distSq;
            if (score > bestScore) {
                bestScore = score;
                best = door;
            }
        }

        if (best == null) {
            return null;
        }
        return approachOutside(best.pos(), best.outside());
    }

    private static boolean isLikelyCompound(String type) {
        String t = type.toLowerCase(Locale.ROOT);
        return t.contains("village") || t.contains("fort") || t.contains("town") || t.contains("city");
    }

    /** Palette query: empty/non-door sections need no block-by-block scan. */
    public static boolean sectionHasDoor(LevelChunkSection section) {
        return section != null && section.maybeHas(state -> !state.isAir()
                && DOOR_STATES.computeIfAbsent(state, value -> isLowerDoor(lower(value))));
    }

    private static boolean isLowerDoor(String text) {
        return text != null
                && text.contains("door")
                && !text.contains("trapdoor")
                && text.contains("half=lower");
    }

    /**
     * Door facing only gives us the door normal, not reliably which side is
     * outdoors in every template. Probe both normals and choose the side with
     * more open air/natural footing and less building material.
     */
    private static Direction2D chooseExteriorDirection(ServerLevel world, BlockPos door, Direction2D facing) {
        Direction2D opposite = facing.opposite();
        int forward = exteriorScore(world, door, facing);
        int backward = exteriorScore(world, door, opposite);
        if (forward == Integer.MIN_VALUE || backward == Integer.MIN_VALUE) {
            return null;
        }
        return backward > forward ? opposite : facing;
    }

    private static int exteriorScore(ServerLevel world, BlockPos door, Direction2D direction) {
        int score = 0;
        int sideX = -direction.dz();
        int sideZ = direction.dx();

        for (int step = 1; step <= OUTSIDE_PROBE; step++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                int x = door.getX() + direction.dx() * step + sideX * lateral;
                int z = door.getZ() + direction.dz() * step + sideZ * lateral;
                BlockPos base = new BlockPos(x, door.getY(), z);

                for (int dy = 0; dy <= 2; dy++) {
                    BlockState state = loadedState(world, base.above(dy));
                    if (state == null) {
                        return Integer.MIN_VALUE;
                    }
                    String id = lower(state);
                    if (state.isAir()) {
                        score += 3;
                    } else if (RoadStructureProtection.isReplaceableOverlay(state)) {
                        score += 2;
                    } else if (GooskerRoadVegetation.isTreeMaterial(state)) {
                        score += 1;
                    } else if (!state.getFluidState().isEmpty()) {
                        score -= 6;
                    } else if (RoadStructureProtection.isStrongBuildingBlock(state)
                            || id.contains("door") || id.contains("window") || id.contains("glass")) {
                        score -= 7;
                    } else {
                        score -= 1;
                    }
                }

                BlockState footing = loadedState(world, base.below());
                if (footing == null) {
                    return Integer.MIN_VALUE;
                }
                if (RoadStructureProtection.isStrongBuildingBlock(footing)) {
                    score -= 4;
                } else if (!footing.isAir() && footing.getFluidState().isEmpty()) {
                    score += 2;
                } else {
                    score -= 4;
                }
            }
        }

        return score;
    }

    /**
     * Checks the complete doorway-probe footprint with getChunkNow(). This is
     * deliberately stronger than hasChunkAt()/hasChunk(): a chunk holder can
     * exist while its FULL LevelChunk is still completing, which is exactly
     * the state that caused alpha-0.2.15 to enter getChunkBlocking.
     */
    private static boolean scanAreaReady(ServerLevel world, BlockPos center, int radius) {
        ServerChunkCache chunks = world.getChunkSource();
        if (chunks == null) {
            return false;
        }
        int minCx = (center.getX() - radius) >> 4;
        int maxCx = (center.getX() + radius) >> 4;
        int minCz = (center.getZ() - radius) >> 4;
        int maxCz = (center.getZ() + radius) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (chunks.getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Non-loading block lookup. Never call ServerLevel#getBlockState here. */
    private static BlockState loadedState(ServerLevel world, BlockPos pos) {
        ServerChunkCache chunks = world.getChunkSource();
        if (chunks == null) {
            return null;
        }
        LevelChunk chunk = chunks.getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    private static Direction2D parsedFacing(String stateText) {
        if (stateText.contains("facing=west")) return new Direction2D(-1, 0);
        if (stateText.contains("facing=east")) return new Direction2D(1, 0);
        if (stateText.contains("facing=north")) return new Direction2D(0, -1);
        if (stateText.contains("facing=south")) return new Direction2D(0, 1);
        return null;
    }

    /**
     * Pick the nearest four-block pathfinding grid cell that is still at least
     * APPROACH_CLEARANCE blocks outside the entrance. Lateral snapping is to
     * the nearest grid line rather than always flooring, reducing visible gaps.
     */
    private static BlockPos approachOutside(BlockPos door, Direction2D outside) {
        int step = PathFinder.GRID_STEP;
        int x;
        int z;

        if (outside.dx() > 0) {
            x = ceilToGrid(door.getX() + APPROACH_CLEARANCE, step);
            z = nearestToGrid(door.getZ(), step);
        } else if (outside.dx() < 0) {
            x = floorToGrid(door.getX() - APPROACH_CLEARANCE, step);
            z = nearestToGrid(door.getZ(), step);
        } else if (outside.dz() > 0) {
            x = nearestToGrid(door.getX(), step);
            z = ceilToGrid(door.getZ() + APPROACH_CLEARANCE, step);
        } else {
            x = nearestToGrid(door.getX(), step);
            z = floorToGrid(door.getZ() - APPROACH_CLEARANCE, step);
        }
        return new BlockPos(x, door.getY(), z);
    }

    private static int floorToGrid(int value, int step) {
        return Math.floorDiv(value, step) * step;
    }

    private static int ceilToGrid(int value, int step) {
        return -Math.floorDiv(-value, step) * step;
    }

    private static int nearestToGrid(int value, int step) {
        int floor = floorToGrid(value, step);
        int ceil = ceilToGrid(value, step);
        return Math.abs(value - floor) <= Math.abs(ceil - value) ? floor : ceil;
    }

    private static String lower(BlockState state) {
        return state == null ? "" : String.valueOf(state).toLowerCase(Locale.ROOT);
    }

    private record DoorCandidate(BlockPos pos, Direction2D outside, int openness) {}

    private record Direction2D(int dx, int dz) {
        Direction2D opposite() {
            return new Direction2D(-dx, -dz);
        }
    }
}
