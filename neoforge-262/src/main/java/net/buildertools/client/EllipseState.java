package net.buildertools.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.Set;

/**
 * Client-side state for the Ellipse mechanic (hold ALT+E with a block in hand): while ALT+E is
 * held the client records every vanilla block the player places ({@link #recordPlacement}) into a
 * region box, and the next LMB click sends that region to the server ({@code EllipsePacket}),
 * which turns it into a complete, closed elliptical ring of voussoirs. The region itself defines
 * the ellipse - its extents are the semi-axes and its center is the ellipse center - so no drag
 * is needed (unlike the arch mechanic's stretch step).
 */
@OnlyIn(Dist.CLIENT)
public final class EllipseState {
    private static boolean active;
    private static final Set<BlockPos> placed = new HashSet<>();
    private static BlockPos min;
    private static BlockPos max;

    private EllipseState() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Enters ellipse mode (ALT+E held with a block in hand). */
    public static void begin() {
        if (active) {
            return;
        }
        active = true;
        placed.clear();
        min = null;
        max = null;
    }

    /** Leaves ellipse mode (ALT+E released, or the held item changed). Resets everything. */
    public static void end() {
        active = false;
        placed.clear();
        min = null;
        max = null;
    }

    public static boolean hasRegion() {
        return min != null && max != null;
    }

    public static BlockPos regionMin() {
        return min;
    }

    public static BlockPos regionMax() {
        return max;
    }

    /** The region as an AABB (min corner .. max+1), or null when nothing was placed yet. */
    public static AABB regionBox() {
        if (min == null || max == null) {
            return null;
        }
        return new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1, max.getY() + 1, max.getZ() + 1);
    }

    /** Records a block the player placed while in ellipse mode and grows the region. */
    public static void recordPlacement(BlockPos pos) {
        BlockPos p = pos.immutable();
        if (placed.add(p)) {
            min = min == null ? p : new BlockPos(
                    Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ()));
            max = max == null ? p : new BlockPos(
                    Math.max(max.getX(), p.getX()), Math.max(max.getY(), p.getY()), Math.max(max.getZ(), p.getZ()));
        }
    }

    /** Clears the recorded region after a successful ellipse; stays in ellipse mode. */
    public static void completeEllipse() {
        placed.clear();
        min = null;
        max = null;
    }
}
