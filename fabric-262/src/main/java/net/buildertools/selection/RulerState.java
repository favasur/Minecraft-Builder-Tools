package net.buildertools.selection;

import net.minecraft.core.BlockPos;

/**
 * Client-side state for the Ruler tool: the two measured points.
 */
public final class RulerState {
    private static BlockPos pointA;
    private static BlockPos pointB;

    private RulerState() {
    }

    public static void setPointA(BlockPos pos) {
        pointA = pos.immutable();
    }

    public static void setPointB(BlockPos pos) {
        pointB = pos.immutable();
    }

    public static BlockPos getPointA() {
        return pointA;
    }

    public static BlockPos getPointB() {
        return pointB;
    }

    public static boolean hasMeasurement() {
        return pointA != null && pointB != null;
    }

    public static void clear() {
        pointA = null;
        pointB = null;
    }
}
