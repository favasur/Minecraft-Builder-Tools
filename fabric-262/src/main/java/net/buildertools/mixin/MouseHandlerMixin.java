package net.buildertools.mixin;

import net.buildertools.client.ClientEvents;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric API 26.2 has no raw mouse events, so the tool press/release/scroll handling hooks into
 * the vanilla callbacks directly. When a hook consumes the event, the vanilla click handling
 * (block breaking, item use, scrolling) is skipped for that input.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true)
    private void buildertools$onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (ClientEvents.onMousePress(info.button(), action, info.modifiers())) {
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
