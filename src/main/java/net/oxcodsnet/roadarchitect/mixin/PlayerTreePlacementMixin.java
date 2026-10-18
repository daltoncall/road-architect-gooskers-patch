package net.oxcodsnet.roadarchitect.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerLevel;
import net.oxcodsnet.roadarchitect.storage.PlayerPlacedTreeBlocks;
import net.oxcodsnet.roadarchitect.util.GooskerRoadVegetation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class PlayerTreePlacementMixin {
    @Inject(method="place", at=@At("RETURN"))
    private void roadarchitect$recordPlacement(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> info) {
        if(info.getReturnValue().consumesAction() && context.getPlayer()!=null
                && context.getLevel() instanceof ServerLevel level) {
            var pos=context.getClickedPos();
            var state=level.getBlockState(pos);
            if(GooskerRoadVegetation.isTreeMaterial(state)||state.is(net.minecraft.world.level.block.Blocks.BAMBOO)) {
                PlayerPlacedTreeBlocks.get(level).mark(pos);
            }
        }
    }
}
