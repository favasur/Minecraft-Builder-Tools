package net.buildertools.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Raw mouse hooks for the tool interactions (selection-handle grabbing, entity dragging, brush
 * clicks, off-grid placement, scroll zoom). Fabric's screen events only cover GUIs, so the
 * canonical {@code InputEvent} handlers are fed straight from {@code MouseHandler} - the same
 * injection point NeoForge patches (26.2 renamed the press handler and moved the button info
 * into a record).
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("HEAD"), cancellable = true)
    private void buildertools$mouseButtonPre(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        InputEvent.MouseButton.Pre event = new InputEvent.MouseButton.Pre(buttonInfo.button(), action);
        NeoForge.EVENT_BUS.fire(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("TAIL"))
    private void buildertools$mouseButtonPost(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        NeoForge.EVENT_BUS.fire(new InputEvent.MouseButton.Post(buttonInfo.button(), action));
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void buildertools$mouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        InputEvent.MouseScrollingEvent event = new InputEvent.MouseScrollingEvent(vertical);
        NeoForge.EVENT_BUS.fire(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }
}
