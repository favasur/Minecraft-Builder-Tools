package net.buildertools.mixin;

import net.buildertools.util.FreeBlockRaycast;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes the game's block raycast see the mod's rotated-block layer: after the vanilla clip, the
 * ray is also tested against the rotated blocks and the result is replaced when a rotated block is
 * closer than whatever the vanilla world hit. The hit is reported as a plain block hit on the
 * block's cell, so selection, breaking, placement and the block outline all work on it.
 */
@Mixin(BlockGetter.class)
public interface BlockGetterMixin {
    @Inject(method = "clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;",
            at = @At("RETURN"), cancellable = true)
    private void buildertools$freeBlockClip(ClipContext context, CallbackInfoReturnable<BlockHitResult> cir) {
        if (!((Object) this instanceof Level level)) {
            return;
        }
        FreeBlockRaycast.Hit free = FreeBlockRaycast.raycast(level, context.getFrom(), context.getTo());
        if (free == null) {
            return;
        }
        BlockHitResult vanilla = cir.getReturnValue();
        if (vanilla == null || vanilla.getType() == HitResult.Type.MISS
                || free.distSq() < vanilla.getLocation().distanceToSqr(context.getFrom())) {
            Direction side = free.side() != null ? free.side() : Direction.UP;
            cir.setReturnValue(new BlockHitResult(free.point(), side, free.cell(), false));
        }
    }
}
