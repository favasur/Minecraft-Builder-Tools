package io.github.favasur.fullslabs.neoforge;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.client.PlacementOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * NeoForge 26.2 entry point for the bundled FullSlabs mod (modid "fullslabs", shipped inside the
 * Builder Tools jar). The vertical-slab capability is grafted directly onto every
 * {@link net.minecraft.world.level.block.SlabBlock} by the shared mixins, so there is nothing to
 * register; the entry point bootstraps the shared init hook and registers the screen-space
 * slab-placement overlay as a GUI layer rendered above all vanilla layers.
 */
@Mod(FullSlabs.MODID)
public final class FullSlabsNeoForge {

    public FullSlabsNeoForge(IEventBus modEventBus) {
        FullSlabs.LOGGER.debug("FullSlabs (NeoForge 26.2) constructing");
        FullSlabs.init();
        modEventBus.addListener(this::registerGuiLayers);
    }

    private void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(FullSlabs.id("placement_overlay"), this::renderOverlay);
    }

    private void renderOverlay(GuiGraphicsExtractor extractor, DeltaTracker deltaTracker) {
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
