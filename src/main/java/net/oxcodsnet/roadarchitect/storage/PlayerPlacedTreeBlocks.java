package net.oxcodsnet.roadarchitect.storage;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.oxcodsnet.roadarchitect.util.PersistentStateUtil;

/** Placement history for player wood/leaves/bamboo, persisted with the world. */
public final class PlayerPlacedTreeBlocks extends SavedData {
    private final LongOpenHashSet positions = new LongOpenHashSet();
    public static PlayerPlacedTreeBlocks get(ServerLevel world) {
        return PersistentStateUtil.get(world, PlayerPlacedTreeBlocks::new, PlayerPlacedTreeBlocks::load,
                "roadarchitect_player_tree_blocks");
    }
    public void mark(BlockPos pos) { if (positions.add(pos.asLong())) setDirty(); }
    public void forget(BlockPos pos) { if (positions.remove(pos.asLong())) setDirty(); }
    public boolean contains(BlockPos pos) { return positions.contains(pos.asLong()); }
    public static PlayerPlacedTreeBlocks load(CompoundTag tag) {
        PlayerPlacedTreeBlocks data = new PlayerPlacedTreeBlocks();
        data.positions.addAll(new LongOpenHashSet(tag.getLongArray("positions")));
        return data;
    }
    public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("positions", positions.toLongArray()); return tag;
    }
}
