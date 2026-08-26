package net.buildertools;

import io.github.favasur.fullslabs.client.PlacementOverlay;
import io.github.favasur.smoothterrain.fabric.ClientInit;
import net.buildertools.client.ClientEvents;
import net.buildertools.client.RotatedBlockRenderer;
import net.buildertools.client.SelectionRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client entrypoint: runs after {@link BuilderToolsMod#onInitialize()}, so the client-side
 * handlers are registered on the game bus here and only then are the client Fabric hooks
 * wired (so no event is fired before its listeners exist).
 */
public final class BuilderToolsModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientInit.register();
        NeoForge.EVENT_BUS.register(ClientEvents.class);
        NeoForge.EVENT_BUS.register(SelectionRenderer.class);
        NeoForge.EVENT_BUS.register(RotatedBlockRenderer.class);
        FabricHooks.registerClient();
        HudRenderCallback.EVENT.register(BuilderToolsModClient::renderPlacementOverlay);
    }

    /** FullSlabs screen-space slab-placement overlay (edge/fill highlight while placing). */
    private static void renderPlacementOverlay(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        PlacementOverlay.render(new PlacementOverlay.OverlayDraw() {
            @Override
            public int width() {
                return guiGraphics.guiWidth();
            }

            @Override
            public int height() {
                return guiGraphics.guiHeight();
            }

            @Override
            public void fill(int x1, int y1, int x2, int y2, int color) {
                guiGraphics.fill(x1, y1, x2, y2, color);
            }

            @Override
            public void hLine(int x1, int x2, int y, int color) {
                guiGraphics.hLine(x1, x2, y, color);
            }

            @Override
            public void vLine(int x1, int y1, int y2, int color) {
                guiGraphics.vLine(x1, y1, y2, color);
            }
        }, Minecraft.getInstance());
    }
}
