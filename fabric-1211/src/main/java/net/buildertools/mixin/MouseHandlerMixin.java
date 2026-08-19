package net.buildertools.mixin;

import net.buildertools.client.ClientEvents;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric API 1.21.1 has no raw mouse events, so the tool press/release/scroll handling hooks into
 * the vanilla callbacks directly. When a hook consumes the event, the vanilla click handling
 * (block breaking, item use, scrolling) is skipped for that input.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), cancellable = true)
    private void buildertools$onPress(long window, int button, int action, int mods, CallbackInfo ci) {
        if (ClientEvents.onMousePress(button, action, mods)) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void buildertools$onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (ClientEvents.onMouseScroll(yOffset)) {
            ci.cancel();
        }
    }
}
