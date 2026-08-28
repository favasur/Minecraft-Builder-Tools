package net.buildertools.mixin;

import net.buildertools.common.NoClipState;
import net.buildertools.server.BuilderServerHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code Player.tick()} starts by resetting {@code noPhysics = isSpectator()} and only then runs
 * the movement code (super.tick), so any no-clip flag applied by a tick event is wiped before the
 * player's collision check. This injects a re-apply right after the reset, so movement in the same
 * tick sees {@code noPhysics = true} for no-clip players on both the client (local prediction) and
 * the server (authoritative movement).
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
    @Inject(method = "tick",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z",
                    ordinal = 0,
                    shift = At.Shift.AFTER))
    private void buildertools$reapplyNoClip(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        boolean enabled = self.level().isClientSide()
                ? NoClipState.isClientEnabled()
                : BuilderServerHandler.hasNoClip(self);
        if (enabled) {
            self.noPhysics = true;
            // No Clip must not dump the player through the floor when they are not already flying:
            // noPhysics skips collision entirely, so gravity would pull them through the ground.
            // Granting (and engaging) flight cancels gravity while keeping the pass-through
            // behaviour, mirroring what the server-side toggle (applyPlayerAbilities) already does.
            if (!self.getAbilities().mayfly || !self.getAbilities().flying) {
                self.getAbilities().mayfly = true;
                self.getAbilities().flying = true;
            }
        }
    }
}
