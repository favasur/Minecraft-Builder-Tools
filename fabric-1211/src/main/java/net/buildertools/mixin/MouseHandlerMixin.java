package net.buildertools.mixin;

import net.minecraft.client.MouseHandler;
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
 * injection point NeoForge patches.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), cancellable = true)
    private void buildertools$mousePressPre(long window, int button, int action, int mods, CallbackInfo ci) {
        InputEvent.MouseButton.Pre event = new InputEvent.MouseButton.Pre(button, action);
        NeoForge.EVENT_BUS.fire(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }

    @Inject(method = "onPress(JIII)V", at = @At("TAIL"))
    private void buildertools$mousePressPost(long window, int button, int action, int mods, CallbackInfo ci) {
        NeoForge.EVENT_BUS.fire(new InputEvent.MouseButton.Post(button, action));
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
