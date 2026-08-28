package io.github.favasur.fullslabs.mixin;

import io.github.favasur.fullslabs.block.SlabVertical;
import io.github.favasur.fullslabs.config.Controls;
import io.github.favasur.fullslabs.util.SlabPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The 1.21.1 vertical-slab graft: adds the {@code vertical}/{@code direction} state properties to
 * every {@link SlabBlock} (vanilla or modded) and wires placement, shapes and replacement for
 * them, so any slab item can be placed standing on its edge without a single new block id.
 *
 * <p>Placement mirrors the original FullSlabs rules: clicking the side of a block (per the
 * placement mode) stands the slab vertically against it, clicking a same-material vertical
 * slab's inner face merges it into a full double slab, and top/bottom clicks keep vanilla.
 */
@Mixin(SlabBlock.class)
public abstract class SlabBlockMixin {

    @Inject(method = "createBlockStateDefinition(Lnet/minecraft/world/level/block/state/StateDefinition$Builder;)V", at = @At("HEAD"))
    private void fullslabs$stateDefinition(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(SlabVertical.VERTICAL, SlabVertical.DIRECTION);
    }

    @Inject(method = "getShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true)
    private void fullslabs$shape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        if (SlabVertical.isVertical(state)) {
            cir.setReturnValue(SlabVertical.shape(state));
        }
    }

    @Inject(method = "useShapeForLightOcclusion(Lnet/minecraft/world/level/block/state/BlockState;)Z", at = @At("HEAD"), cancellable = true)
    private void fullslabs$lightOcclusion(BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (SlabVertical.isVertical(state)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getStateForPlacement(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
    private void fullslabs$placement(BlockPlaceContext ctx, CallbackInfoReturnable<BlockState> cir) {
        SlabBlock self = this.fullslabs$self();
        if (!self.defaultBlockState().hasProperty(SlabVertical.VERTICAL)) {
            return; // a slab subclass without the grafted properties keeps vanilla placement
        }
        BlockPos pos = ctx.getClickedPos();
        Level world = ctx.getLevel();
        BlockState state = world.getBlockState(pos);
        if (state.is(self)) {
            if (SlabVertical.isVertical(state)) {
                // Merging with a vertical slab of the same material: fill the block (vanilla double).
                cir.setReturnValue(state.setValue(SlabVertical.VERTICAL, false)
                        .setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)
                        .setValue(BlockStateProperties.WATERLOGGED, false));
            } else {
                cir.setReturnValue(state.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE)
                        .setValue(BlockStateProperties.WATERLOGGED, false));
            }
            return;
        }
        if (state.getBlock() instanceof SlabBlock) {
            // A different slab material cannot share the block space; BlockItem then falls back to
            // placing into the adjacent block against the clicked side (vertical placement there).
            cir.setReturnValue(state);
            return;
        }
        Direction face = ctx.getClickedFace();
        FluidState fluidState = world.getFluidState(pos);
        Player player = ctx.getPlayer();
        Direction target;
        if (player == null) {
            target = Direction.DOWN;
        } else {
            target = SlabPlacement.getTargetedDirection(
                    Controls.getPlacementMode(player.getUUID()), face, ctx.getHorizontalDirection(),
                    pos, ctx.getClickLocation());
        }
        cir.setReturnValue(SlabVertical.getTargetedState(self, face, target, ctx.getRotation())
                .setValue(BlockStateProperties.WATERLOGGED, fluidState.is(Fluids.WATER)));
    }

    @Inject(method = "canBeReplaced(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/item/context/BlockPlaceContext;)Z", at = @At("HEAD"), cancellable = true)
    private void fullslabs$replacement(BlockState state, BlockPlaceContext ctx, CallbackInfoReturnable<Boolean> cir) {
        if (!SlabVertical.isVertical(state)) {
            return; // horizontal slab states keep the vanilla replacement rules
        }
        ItemStack stack = ctx.getItemInHand();
        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == this.fullslabs$self()) {
            // Same material: clicking the inner face region merges into a full block; the body
            // clicks fall through to the adjacent-block placement (mirrors the original behavior).
            cir.setReturnValue(ctx.replacingClickedOnBlock()
                    ? SlabVertical.isInsideSlab(state, ctx.getClickedPos(), ctx.getClickLocation())
                    : true);
            return;
        }
        cir.setReturnValue(false);
    }

    @Unique
    private SlabBlock fullslabs$self() {
        return (SlabBlock) (Object) this;
    }
}
