package io.github.favasur.fullslabs.mixin;

import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.config.Controls;
import io.github.favasur.fullslabs.handlers.MixedHandlers;
import io.github.favasur.fullslabs.util.SlabPlacement;
import io.github.favasur.fullslabs.util.Utility;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={SlabBlock.class})
public class SlabBlockMixin {
    @Inject(method={"getStateForPlacement(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"}, at={@At(value="HEAD")}, cancellable=true)
    private void editPlacementRules(BlockPlaceContext ctx, CallbackInfoReturnable<BlockState> cir) {
        SlabBlock self = this.fullslabs$self();
        if (!VerticalSlabBlock.hasVertical(self)) {
            return;
        }
        BlockPos pos = ctx.getClickedPos();
        Level world = ctx.getLevel();
        BlockState state = world.getBlockState(pos);
        if (state.is(VerticalSlabBlock.getVertical(self))) {
            // The inner (cut) face of a vertical slab only fills to FULL; in hybrid mode its edge
            // regions also place an adjacent slab (mirroring the outer edge faces), while the
            // center keeps the fill behaviour.
            if (Utility.isInsideSlab(state, pos, ctx.getClickLocation())) {
                Player player = ctx.getPlayer();
                if (player != null && Controls.getPlacementMode(player.getUUID()) == SlabPlacement.Mode.HYBRID) {
                    Direction clickedFace = ctx.getClickedFace();
                    Direction target = SlabPlacement.getTargetedDirection(SlabPlacement.Mode.HYBRID, clickedFace, ctx.getHorizontalDirection(), pos, ctx.getClickLocation());
                    if (target != clickedFace) {
                        cir.setReturnValue(Utility.getTargetedState(self, clickedFace, target, ctx.getRotation())
                                .setValue(BlockStateProperties.WATERLOGGED, world.getFluidState(pos).is(Fluids.WATER)));
                        return;
                    }
                }
            }
            cir.setReturnValue(state.setValue(VerticalSlabBlock.TYPE, VerticalSlabBlock.VerticalType.FULL).setValue(BlockStateProperties.WATERLOGGED, false));
        } else if (state.is(self)) {
            cir.setReturnValue(state.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE).setValue(BlockStateProperties.WATERLOGGED, false));
        } else if (Utility.isSlab(state)) {
            cir.setReturnValue(SlabRegistry.MIXED_SLAB.defaultBlockState().setValue(MixedSlabBlock.TYPE, MixedSlabBlock.MixedType.fromState(state)));
        } else {
            Direction target;
            Direction face = ctx.getClickedFace();
            FluidState fluidState = world.getFluidState(pos);
            Player player = ctx.getPlayer();
            if (player == null) {
                target = Direction.DOWN;
            } else {
                SlabPlacement.Mode mode = Controls.getPlacementMode(ctx.getPlayer().getUUID());
                target = SlabPlacement.getTargetedDirection(mode, face, ctx.getHorizontalDirection(), pos, ctx.getClickLocation());
            }
            cir.setReturnValue(Utility.getTargetedState(this.fullslabs$self(), face, target, ctx.getRotation()).setValue(BlockStateProperties.WATERLOGGED, fluidState.is(Fluids.WATER)));
        }
    }

    @Inject(method={"canBeReplaced(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/item/context/BlockPlaceContext;)Z"}, at={@At(value="HEAD")}, cancellable=true)
    private void editReplacementRules(BlockState state, BlockPlaceContext context, CallbackInfoReturnable<Boolean> cir) {
        BlockItem blockItem;
        Block block;
        Item item;
        SlabBlock self = this.fullslabs$self();
        if (!VerticalSlabBlock.hasVertical(self)) {
            return;
        }
        ItemStack stack = context.getItemInHand();
        SlabType type = (SlabType)state.getValue((Property)BlockStateProperties.SLAB_TYPE);
        if (type != SlabType.DOUBLE && (item = stack.getItem()) instanceof BlockItem && (block = (blockItem = (BlockItem)item).getBlock()) instanceof SlabBlock && (block == self || MixedHandlers.hasHandler(block) && MixedHandlers.hasHandler((Block)self))) {
            if (context.replacingClickedOnBlock()) {
                cir.setReturnValue(Utility.isInsideSlab(state, context.getClickedPos(), context.getClickLocation()));
                return;
            }
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(false);
    }

    @Unique
    private SlabBlock fullslabs$self() {
        return (SlabBlock)(Object)this;
    }
}

