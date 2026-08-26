package io.github.favasur.fullslabs.mixin.client;

import io.github.favasur.fullslabs.block.SlabVertical;
import io.github.favasur.fullslabs.client.models.VerticalSlabModel;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side hook that makes vertical slab states render. {@code BlockModelShaper#getBlockModel}
 * is the single per-state model choke point of the 1.21.1 renderer, so a vertical slab state
 * simply gets a {@link VerticalSlabModel} wrapping the same block's horizontal bottom model.
 * This applies to every {@link net.minecraft.world.level.block.SlabBlock} in the game without
 * any resource files.
 */
@Mixin(BlockModelShaper.class)
public class BlockModelShaperMixin {

    @Inject(method = "getBlockModel(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/resources/model/BakedModel;", at = @At("HEAD"), cancellable = true)
    private void fullslabs$verticalModel(BlockState state, CallbackInfoReturnable<BakedModel> cir) {
        if (!SlabVertical.isVertical(state)) {
            return;
        }
        // The flat (bottom, non-vertical) state takes the original path, so this recursion terminates.
        BakedModel parent = ((BlockModelShaper) (Object) this).getBlockModel(SlabVertical.flat(state));
        cir.setReturnValue(new VerticalSlabModel(parent, SlabVertical.occupiedHalf(state)));
    }
}
