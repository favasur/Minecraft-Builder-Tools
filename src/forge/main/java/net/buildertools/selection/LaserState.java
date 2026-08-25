package net.buildertools.selection;

import net.minecraft.core.BlockPos;

/**
 * Client-side state for the Laser tool: remembers the last block the beam hit so the action bar
 * reading only updates when the target actually changes.
 */
public final class LaserState {
    private static BlockPos lastTarget;
    private static double lastDistance;

    private LaserState() {
    }

    public static BlockPos getLastTarget() {
        return lastTarget;
    }

    public static double getLastDistance() {
        return lastDistance;
    }

    public static void update(BlockPos target, double distance) {
        lastTarget = target;
        lastDistance = distance;
    }
}
