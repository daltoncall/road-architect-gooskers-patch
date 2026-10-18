package net.oxcodsnet.roadarchitect.storage;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.oxcodsnet.roadarchitect.util.PersistentStateUtil;
import net.oxcodsnet.roadarchitect.util.TreeBuildProtection;

/** Saved no-drop edits: one chunk, at most 128 candidates, then six ticks of rest. */
public final class TreeRemovalQueue extends SavedData {
    private final LinkedHashMap<Long,LinkedHashMap<Long,Cell>> pending=new LinkedHashMap<>();
    private final Map<Long,Long> eligibleAfter=new HashMap<>();
    private int size;
    private long pass;
    public record Cell(BlockPos pos,String blockId,boolean snowOnly) {}
    public static TreeRemovalQueue get(ServerLevel world) {
        return PersistentStateUtil.get(world,TreeRemovalQueue::new,TreeRemovalQueue::load,"roadarchitect_tree_removal");
    }
    private static long chunkKey(BlockPos pos) { return ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4); }
    public boolean contains(BlockPos pos) {
        var chunk=pending.get(chunkKey(pos));return chunk!=null&&chunk.containsKey(pos.asLong());
    }
    public int size() { return size; }
    public boolean hasPendingAround(ChunkPos center,int radius) {
        for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++)
            if(pending.containsKey(ChunkPos.asLong(center.x+dx,center.z+dz))) return true;
        return false;
    }
    public void add(Collection<Cell> cells) {
        for(Cell cell:cells) {
            var chunk=pending.computeIfAbsent(chunkKey(cell.pos()),ignored->new LinkedHashMap<>());
            if(chunk.putIfAbsent(cell.pos().asLong(),cell)==null) size++;
        }
        if(!cells.isEmpty()) setDirty();
    }
    public int process(ServerLevel world) {
        Map<Long,java.util.function.Predicate<BlockPos>> guards=new HashMap<>();
        return process(world,pos->guards.computeIfAbsent(chunkKey(pos),ignored->TreeBuildProtection.snapshot(world,pos)).test(pos));
    }
    public int process(WorldGenLevel world,java.util.function.Predicate<BlockPos> protectedPos) {
        pass++;
        int candidates=Math.min(8,pending.size());
        for(int inspectedChunks=0;inspectedChunks<candidates;inspectedChunks++) {
            var entry=pending.entrySet().iterator().next();long key=entry.getKey();var cells=entry.getValue();
            pending.remove(key);
            if(pass<eligibleAfter.getOrDefault(key,0L)) { pending.put(key,cells);continue; }
            var first=cells.values().iterator().next();
            if(read(world,first.pos())==null) { pending.put(key,cells);continue; }
            int checked=0,removed=0;List<Cell> retry=new ArrayList<>();
            var iterator=cells.values().iterator();
            while(iterator.hasNext()&&checked++<128) {
                Cell cell=iterator.next();BlockState state=read(world,cell.pos());
                if(state==null||(world instanceof ServerLevel level&&TreeBuildProtection.structureInfoMissing(level,cell.pos()))) {
                    retry.add(cell);iterator.remove();continue;
                }
                if(!protectedPos.test(cell.pos())&&BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(cell.blockId())) {
                    boolean remove=true;
                    if(cell.snowOnly()) {
                        BlockState below=read(world,cell.pos().below());
                        remove=state.is(Blocks.SNOW)&&below!=null&&(below.isAir()||!below.getFluidState().isEmpty());
                    }
                    if(remove) { world.removeBlock(cell.pos(),false);removed++; }
                }
                iterator.remove();size--;
            }
            for(Cell cell:retry) cells.put(cell.pos().asLong(),cell);
            if(!cells.isEmpty()) { pending.put(key,cells);eligibleAfter.put(key,pass+7); }
            else eligibleAfter.remove(key);
            if(checked>0) setDirty();
            return removed; // never spread a deletion burst across several chunks
        }
        return 0;
    }
    private static BlockState read(WorldGenLevel world,BlockPos pos) {
        if(world instanceof ServerLevel level) {
            var chunk=level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            return chunk==null?null:chunk.getBlockState(pos);
        }
        return world.hasChunk(pos.getX()>>4,pos.getZ()>>4)?world.getBlockState(pos):null;
    }
    public static TreeRemovalQueue load(CompoundTag tag) {
        TreeRemovalQueue queue=new TreeRemovalQueue();ListTag list=tag.getList("pending",10);
        List<Cell> cells=new ArrayList<>();
        for(int i=0;i<list.size();i++) {
            CompoundTag cell=list.getCompound(i);
            cells.add(new Cell(BlockPos.of(cell.getLong("pos")),cell.getString("id"),cell.getBoolean("snow")));
        }
        queue.add(cells);return queue;
    }
    public CompoundTag save(CompoundTag tag) {
        ListTag list=new ListTag();
        for(var chunk:pending.values()) for(Cell cell:chunk.values()) {
            CompoundTag value=new CompoundTag();value.putLong("pos",cell.pos().asLong());
            value.putString("id",cell.blockId());value.putBoolean("snow",cell.snowOnly());list.add(value);
        }
        tag.put("pending",list);return tag;
    }
}
