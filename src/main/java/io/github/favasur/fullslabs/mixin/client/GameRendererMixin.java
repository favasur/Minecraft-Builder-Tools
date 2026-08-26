package io.github.favasur.fullslabs.mixin.client;

import io.github.favasur.fullslabs.client.PlacementOverlay;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.1-only helper for the placement overlay: {@code GameRenderer#getFov} is private, but the
 * overlay needs the actual rendered field of view (sprint/fly/spyglass-adjusted) to project the
 * target slab volume onto the HUD. This caches the value on every call; the HUD overlay renders
 * after the level render each frame, so the cached fov is always the current frame's.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)D", at = @At("RETURN"))
    private void fullslabs$cacheFov(Camera camera, float partialTick, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        PlacementOverlay.lastFov = cir.getReturnValue().floatValue();
    }
}
