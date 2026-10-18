package net.oxcodsnet.roadarchitect.handlers.compat;

import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Check only nests/snow discovered during the existing bounded tree pass. */
public final class GooskerBeeNestCleanup {
    private GooskerBeeNestCleanup() {}

    public static void cleanupCandidates(WorldGenLevel world, Collection<BlockPos> nests,
                                         Collection<BlockPos> snow) {
        for (BlockPos pos : nests) {
            BlockState state = loadedState(world, pos);
            if (state != null && state.is(Blocks.BEE_NEST)) world.removeBlock(pos, false);
        }
        for (BlockPos pos : snow) {
            BlockState state = loadedState(world, pos);
            if (state == null || !state.is(Blocks.SNOW)) continue;
            BlockState below = loadedState(world, pos.below());
            if (below != null && (below.isAir() || !below.getFluidState().isEmpty())) {
                world.removeBlock(pos, false);
            }
        }
    }

    private static BlockState loadedState(WorldGenLevel world, BlockPos pos) {
        if (world instanceof ServerLevel level) {
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockState(pos);
        }
        return world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? world.getBlockState(pos) : null;
    }
}
