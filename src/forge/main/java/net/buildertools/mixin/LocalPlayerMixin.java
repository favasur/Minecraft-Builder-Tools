package net.buildertools.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hytale-style sprinting: holding W + Caps Lock sprints instead of the vanilla double-tap-W.
 * The double-tap is disabled by pinning {@code sprintTriggerTime} to zero every tick, so the
 * vanilla "second tap inside the window" check never fires; Ctrl-sprint keeps working.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Shadow
    protected int sprintTriggerTime;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void builderTools$disableDoubleTapSprint(CallbackInfo ci) {
        this.sprintTriggerTime = 0;
    }

    @Inject(method = "aiStep", at = @At("TAIL"))
    private void builderTools$capsLockSprint(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        Minecraft mc = Minecraft.getInstance();
        boolean capsLock = InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_CAPS_LOCK);
        if (self.input.hasForwardImpulse()
                && mc.options.keyUp.isDown()
                && capsLock
                && !self.isSprinting()
                && !self.isUsingItem()
                && !self.hasEffect(MobEffects.BLINDNESS)
                && !self.isPassenger()
                && !self.isInWater()
                && !self.isInLava()
                && self.onGround()) {
            self.setSprinting(true);
        }
    }
}
