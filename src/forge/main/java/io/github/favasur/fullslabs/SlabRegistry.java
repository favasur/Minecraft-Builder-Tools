package io.github.favasur.fullslabs;

import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.List;

/**
 * Forge 1.21.1 registry adapter for the bundled FullSlabs mod (modid "fullslabs", shipped inside
 * the Builder Tools jar). The 1.21.1 port is a pure graft: the vertical-slab capability is applied
 * directly to every {@link net.minecraft.world.level.block.SlabBlock} by the shared mixins
 * ({@code SlabBlockMixin} + {@code client.models.VerticalSlabModel}, wired by
 * {@code mixin.client.BlockModelShaperMixin}) and rendered by the screen-space placement overlay.
 * No blocks, items or block-entity types are registered, so init is deliberately a no-op marker;
 * the mixins are self-registering. Kept as a small class (rather than removed) so the entry point
 * keeps its registry hook symmetry with the other loader adapters.
 */
public final class SlabRegistry {
    private SlabRegistry() {
    }

    /** Common (non-loader) setup. Runs at mod construction; a no-op for the pure-graft port. */
    public static void init(IEventBus bus) {
    }

    /** All registered vertical-slab ids (empty - the graft registers nothing). */
    public static List<String> ids() {
        return List.of();
    }

    /** The vertical-slab id for a block (none - the graft registers nothing). */
    public static String id(Block block) {
        return "";
    }

    /** The vertical-slab block for an id (none - the graft registers nothing). */
    public static Block get(String id) {
        return null;
    }
}
