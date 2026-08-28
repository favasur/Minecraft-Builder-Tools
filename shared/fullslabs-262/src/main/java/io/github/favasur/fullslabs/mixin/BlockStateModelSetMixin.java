package io.github.favasur.fullslabs.mixin;

import io.github.favasur.fullslabs.block.SlabVertical;
import io.github.favasur.fullslabs.client.VerticalSlabModel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side hook that makes vertical slab states render. {@code BlockStateModelSet} is the
 * single per-block model choke point of the 26.2 renderer, so a vertical slab state simply gets
 * a {@link VerticalSlabModel} wrapping the same block's horizontal bottom model. This applies to
 * every {@link net.minecraft.world.level.block.SlabBlock} in the game without any resource files.
 */
@Mixin(BlockStateModelSet.class)
public class BlockStateModelSetMixin {
    private static final Logger LOG = LogManager.getLogger("FullSlabs");

    @Inject(method = "get(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;", at = @At("HEAD"), cancellable = true)
    private void fullslabs$verticalModel(BlockState state, CallbackInfoReturnable<BlockStateModel> cir) {
        if (!SlabVertical.isVertical(state)) {
            return;
        }
        // If this never fires for a state, the vertical model is NOT being substituted (the whole
        // "no visible textures" symptom). Show each vertical state routed to the custom model.
        LOG.debug("BlockStateModelSet routing vertical state -> occupied={} flat={}",
                SlabVertical.occupiedHalf(state), SlabVertical.flat(state));
        // The flat (bottom, non-vertical) state takes the original path, so this recursion terminates.
        BlockStateModel parent = ((BlockStateModelSet) (Object) this).get(SlabVertical.flat(state));
        cir.setReturnValue(new VerticalSlabModel(parent, SlabVertical.occupiedHalf(state)));
    }
}
