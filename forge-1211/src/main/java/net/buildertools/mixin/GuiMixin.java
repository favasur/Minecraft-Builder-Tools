package net.buildertools.mixin;

import net.buildertools.client.ClientEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge 1.21.1 removed {@code RenderGuiEvent}, so the control-hints legend is drawn here, at the
 * very end of {@code Gui.render}, after every vanilla HUD element.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At("RETURN"))
    private void buildertools$renderLegend(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        ClientEvents.renderLegend(guiGraphics);
    }
}
