package net.buildertools.util;

import io.github.favasur.fullslabs.block.SlabVertical;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Bridge between Builder Tools and the bundled FullSlabs module. With the 26.2 graft, slab
 * verticality is a state property on the slab itself, so normalization simply stands the slab up
 * on its edge (used by the rotated-block feature when the held block is a slab).
 */
public final class FullSlabsCompat {

    private FullSlabsCompat() {
    }

    public static BlockState normalize(BlockState state) {
        return SlabVertical.vertical(state);
    }
}
