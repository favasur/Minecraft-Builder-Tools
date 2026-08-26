package io.github.favasur.fullslabs.mixin.client;

import io.github.favasur.fullslabs.client.PlacementOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric 26.2 HUD hook for the FullSlabs slab-placement overlay. Fabric API 26.2 removed
 * {@code HudRenderCallback}, so the overlay draws at the tail of the vanilla HUD extraction
 * instead, through the frame's {@link GuiGraphicsExtractor}.
 */
@Mixin(Hud.class)
public abstract class HudMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
    private void fullslabs$placementOverlay(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker, CallbackInfo ci) {
        PlacementOverlay.render(new PlacementOverlay.OverlayDraw() {
            @Override
            public int width() {
                return extractor.guiWidth();
            }

            @Override
            public int height() {
                return extractor.guiHeight();
            }

            @Override
            public void fill(int x1, int y1, int x2, int y2, int color) {
                extractor.fill(x1, y1, x2, y2, color);
            }

            @Override
            public void hLine(int x1, int x2, int y, int color) {
                extractor.horizontalLine(x1, x2, y, color);
            }

            @Override
            public void vLine(int x1, int y1, int y2, int color) {
                extractor.verticalLine(x1, y1, y2, color);
            }
        }, Minecraft.getInstance());
    }
}
