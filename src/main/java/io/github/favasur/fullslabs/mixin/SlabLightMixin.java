package io.github.favasur.fullslabs.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes every slab state (horizontal bottom/top, double and vertical) fully light-opaque.
 * Vanilla slabs leave the half-empty cell nearly transparent (light dampening 1), so light
 * passes through slab roofs, floors and walls. This mixin returns the maximum dampening for
 * all {@link SlabBlock} states, making slabs block light completely. {@code getLightBlock} is
 * declared in {@link BlockBehaviour} and its result is cached per state, so this mixin targets
 * the base class and only intervenes for slab states.
 */
@Mixin(BlockBehaviour.class)
public abstract class SlabLightMixin {

    @Inject(method = "getLightBlock(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void fullslabs$lightBlock(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (state.getBlock() instanceof SlabBlock) {
            cir.setReturnValue(15);
        }
    }
}
