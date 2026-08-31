package net.buildertools.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A player-placed rotated block living in the mod's own (neighbor-dependent) block layer. The
 * vanilla cell stays AIR - the block exists only in this layer, carrying its real BlockState so it
 * keeps its shading, breaking, drops and picking while rendering and colliding rotated around its
 * model center. A {@code billboard} block always faces the player.
 *
 * <p>The model center is the exact world-space point the block rotates around (the rotation
 * pivot). Blocks placed against a rotated neighbor snap onto its rotated grid, so their centers
 * are fractional and off the vanilla XYZ grid; {@code center} may be null only for entries saved
 * before the center existed (they are treated as cell-centered by {@link #center(BlockPos)}).
 *
 * <p>When {@code arch} is non-null the entry is an arch VOUSSOIR: instead of the block state
 * rotated by yaw/pitch, it renders and collides as the tapered wedge described by
 * {@link ArchBlockData} (yaw/pitch are unused, and the model center is the wedge's centerline
 * pivot). When {@code ellipse} is non-null the entry is an ellipse VOUSSOIR of a closed
 * elliptical ring ({@link EllipseBlockData}); arch and ellipse are mutually exclusive.
 */
public record RotationData(BlockState state, float yaw, float pitch, boolean billboard, Vec3 center,
                           ArchBlockData arch, EllipseBlockData ellipse) {

    /** Cell-centered entry (the standard case: a block placed on the vanilla grid). */
    public RotationData(BlockState state, float yaw, float pitch, boolean billboard) {
        this(state, yaw, pitch, billboard, null, null, null);
    }

    /** Entry with an explicit model center, without arch/ellipse geometry. */
    public RotationData(BlockState state, float yaw, float pitch, boolean billboard, Vec3 center) {
        this(state, yaw, pitch, billboard, center, null, null);
    }

    /** The world-space model center of the block: the explicit center, or the cell center for
     *  entries saved before the center was stored. */
    public Vec3 center(BlockPos cell) {
        return center != null ? center : Vec3.atCenterOf(cell);
    }
}
