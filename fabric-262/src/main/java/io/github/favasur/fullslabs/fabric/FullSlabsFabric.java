package io.github.favasur.fullslabs.fabric;

import io.github.favasur.fullslabs.FullSlabs;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric 26.2 entry point for the bundled FullSlabs mod (modid "fullslabs", shipped inside the
 * Builder Tools jar). The vertical-slab capability is grafted directly onto every
 * {@link net.minecraft.world.level.block.SlabBlock} by the shared mixins, so there is nothing to
 * register; the entry point only bootstraps the shared init hook.
 */
public final class FullSlabsFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        FullSlabs.LOGGER.debug("FullSlabs (Fabric 26.2) initializing");
        FullSlabs.init();
    }
}
