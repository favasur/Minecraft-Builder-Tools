package io.github.favasur.fullslabs.neoforge;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.client.BlockFaceOverlay;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 1.21.1 entry point for the bundled FullSlabs mod (modid "fullslabs", shipped inside
 * the Builder Tools jar). The vertical-slab capability is grafted directly onto every
 * {@link net.minecraft.world.level.block.SlabBlock} by the shared mixins, so there is nothing to
 * register; the entry point bootstraps the shared init hook and registers the in-world
 * slab-placement face overlay (edge/fill highlight on the targeted block face) after entities
 * render.
 */
@Mod(FullSlabs.MODID)
public final class FullSlabsNeoForge {

    public FullSlabsNeoForge(IEventBus modEventBus) {
        FullSlabs.LOGGER.debug("FullSlabs (NeoForge 1.21.1) constructing");
        FullSlabs.init();
        NeoForge.EVENT_BUS.addListener(this::renderOverlay);
    }

    private void renderOverlay(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) {
            return;
        }
        BlockFaceOverlay.renderFaceOverlay(event.getCamera());
    }
}
