package net.buildertools.mixin;

import net.buildertools.server.RotationStore;
import net.buildertools.util.RotationData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Middle-click (pick block) on a rotated block: the cell is air in the vanilla grid, so the
 * pick would read air and abort before resolving anything. NeoForge 21.1 patches the pick flow
 * so the CLIENT resolves the item (read the cell's block state, build the clone stack, pick the
 * slot) and only sends the chosen slot to the server. Redirect the cell read to the rotated
 * block's REAL state, and the rest of the pick flow (clone item stack, creative add, hotbar
 * selection) works unchanged. Also covers air-placed blocks, which live in the same layer.
 *
 * <p>The wildcard method selector matches the NeoForge-patched {@code Minecraft.pickBlock}
 * (the 1.21.2+ unified pick) while still compiling against the vanilla 1.21.1 classpath, which
 * only knows {@code pickBlockOrEntity}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Redirect(method = "pickBlock*",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState buildertools$pickRotatedState(ClientLevel level, BlockPos pos) {
        RotationData rot = RotationStore.get(level, pos);
        if (rot != null) {
            return rot.state();
        }
        return level.getBlockState(pos);
    }
}
