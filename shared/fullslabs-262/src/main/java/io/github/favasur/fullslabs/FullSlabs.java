package io.github.favasur.fullslabs;

import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Shared FullSlabs bootstrap used by the NeoForge, Forge and Fabric 26.2 adapters.
 *
 * <p>The 26.2 port is a pure graft: the vertical-slab capability is applied directly to every
 * {@link net.minecraft.world.level.block.SlabBlock} through {@code SlabBlockMixin} and rendered
 * by {@code BlockStateModelSetMixin}. No blocks, items or registries are registered, so init is
 * deliberately a no-op marker; the mixins are self-registering.
 */
public final class FullSlabs {
    public static final String MODID = "fullslabs";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    private FullSlabs() {
    }

    /** Common (non-loader) setup. Runs at mod construction; kept for entry-point symmetry. */
    public static void init() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
