package io.github.favasur.fullslabs.mixin.client;

import io.github.favasur.fullslabs.util.Utility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value={BlockRenderDispatcher.class})
public class BlockRenderDispatcherMixin {
    @ModifyVariable(method={"renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V"}, at=@At(value="HEAD"), argsOnly=true)
    private BlockState changeSlabDamageRender(BlockState state, BlockState ignored, BlockPos pos, BlockAndTintGetter view) {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (!(hit instanceof BlockHitResult)) {
            return state;
        }
        return Utility.targetedHalf((BlockGetter)view, state, pos, hit.getLocation());
    }
}

