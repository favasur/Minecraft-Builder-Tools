package net.buildertools.mixin;

import net.buildertools.server.RotationStore;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Middle-click (pick block) on a rotated block: the cell is air in the vanilla grid, so the
 * server's pick would read air and yield nothing. The pick reads the cell's block state exactly
 * once - redirect that call to the rotated block's REAL state, and the rest of the vanilla pick
 * flow (clone item stack, creative inventory add, survival swap) works unchanged. Also covers
 * air-placed blocks, which live in the same layer.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Redirect(method = "handlePickItemFromBlock",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState buildertools$pickRotatedState(ServerLevel level, BlockPos pos) {
        RotationData rot = RotationStore.get(level, pos);
        if (rot != null) {
            return rot.state();
        }
        return level.getBlockState(pos);
    }
}
