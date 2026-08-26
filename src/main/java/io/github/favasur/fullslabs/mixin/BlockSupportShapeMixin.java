package io.github.favasur.fullslabs.mixin;

import io.github.favasur.fullslabs.block.SlabVertical;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives vertical slab states a support shape that makes their two big faces fully supported
 * (torches, levers, buttons, ladders and similar attach there exactly like on a full block)
 * while their thin edges and half-depth top/bottom stay unsupported, matching horizontal slabs:
 * redstone dust cannot sit on top and nothing attaches to the thin sides. The vanilla shape
 * pipeline otherwise slices a half-block's exposed faces into empty or partial support faces.
 * {@code getBlockSupportShape} is declared in {@link BlockBehaviour}, not {@link SlabBlock},
 * so this mixin targets the base class and only intervenes for vertical slab states.
 */
@Mixin(BlockBehaviour.class)
public abstract class BlockSupportShapeMixin {

    @Inject(method = "getBlockSupportShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true)
    private void fullslabs$supportShape(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
        if (state.getBlock() instanceof SlabBlock && SlabVertical.isVertical(state)) {
            cir.setReturnValue(SlabVertical.supportShape(state));
        }
    }
}
