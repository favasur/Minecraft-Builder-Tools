package net.buildertools.mixin;

import net.buildertools.server.RotationStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the mod's block layer consistent with the world: whenever a vanilla block is set into a
 * cell that holds a rotated block (broken away, replaced by a command or another mod), the layer
 * entry is removed so the free block disappears instead of lingering as a ghost. Placement of a
 * NEW rotated block is unaffected - the layer entry is added after the cell is emptied.\n */
@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void buildertools$cleanupRotation(BlockPos pos, BlockState newState, int flags, int recursionLeft,
                                              CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (!(self instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockState oldState = self.getBlockState(pos);
        if (oldState == newState || !RotationStore.hasRotation(serverLevel, pos)) {
            return;
        }
        // A vanilla block was placed into (or the block in) a rotated cell changed: the free
        // block there is gone.
        RotationStore.remove(serverLevel, pos);
    }
}
