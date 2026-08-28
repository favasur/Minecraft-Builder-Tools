package io.github.favasur.fullslabs.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Loader-neutral bridge from the FullSlabs overlay to Builder Tools' rotated-block layer. The
 * overlay cannot depend on {@code net.buildertools} (the FullSlabs module also builds standalone
 * for Forge), so Builder Tools registers its rotation-store-backed implementation at client
 * startup and the overlay asks through this interface.
 */
public interface RotatedBlockLookup {

    /** Everything the placement math needs to know about the rotated block under the cursor. */
    record Target(Vec3 center, float yaw, float pitch, boolean billboard, AABB shapeBounds, BlockState state) {
    }

    /** The rotated block occupying the given cell, or null when the cell is a plain block. */
    @Nullable
    Target at(Level level, BlockPos pos);

    /** The registered implementation (set once at client startup, read on the render thread). */
    final class Holder {
        private Holder() {
        }

        static volatile RotatedBlockLookup INSTANCE;
    }

    static void set(RotatedBlockLookup lookup) {
        Holder.INSTANCE = lookup;
    }

    @Nullable
    static RotatedBlockLookup get() {
        return Holder.INSTANCE;
    }
}
