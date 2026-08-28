package io.github.favasur.fullslabs.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Placement math for standing a slab against a Builder Tools ROTATED block. Pure math - it only
 * knows the neighbor's rotation, shape bounds, center and the hit point, so it lives in the
 * loader-neutral module and is used by both the placement click path and the placement overlay.
 *
 * <p>The slab is placed in the "landing box": the 1x1x1 box whose face touches the clicked
 * rotated block's face, rotated with the block's orientation. The box center sits half a block
 * off the clicked face plane. The placement target (the same region rule normal blocks use, but
 * evaluated in the neighbor's LOCAL frame) then picks which slab goes in that box:
 *
 * <ul>
 *   <li>CENTER of a side face: a vertical slab standing FLAT against the face, occupying the
 *       half of the landing box next to the block (the original "attached" rule for rotated
 *       blocks; the target direction is the local face normal so the slab hugs the side).</li>
 *   <li>LEFT/RIGHT edges: vertical fin slabs running along the face tangent (full depth against
 *       the face, thin axis along the edge).</li>
 *   <li>TOP/BOTTOM edges: horizontal top/bottom slabs laid flush against the face, rotated with
 *       the block.</li>
 * </ul>
 *
 * <p>Everything is computed in the neighbor's local frame (the inverse rotation maps the world
 * hit point and the player's facing into the block's un-rotated space), then mapped back to the
 * world: the returned box center is the exact world-space model center for the placement packet,
 * and the returned target direction feeds {@link SlabVertical#applyDirection} to build the state.
 */
public final class RotatedSlabPlacement {

    /** The result of a placement: where to put the slab's box and which slab to place. */
    public record Result(Vec3 boxCenter, Direction target) {
    }

    private RotatedSlabPlacement() {
    }

    /**
     * The rotation used by the rotated-block layer. Kept in sync with
     * {@code net.buildertools.util.OffGridTransform#rotation} so the overlay (which cannot depend
     * on Builder Tools) produces exactly the same orientation as the placed display.
     */
    public static Quaternionf rotation(float yawDeg, float pitchDeg) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDeg))
                .mul(new Quaternionf().rotateX((float) Math.toRadians(pitchDeg)));
    }

    /**
     * Computes the placement of a slab against a rotated block.
     *
     * @param mode             the FullSlabs placement mode (hybrid/vanilla/vertical)
     * @param rotation         the neighbor's world rotation (yaw/pitch as stored in the layer)
     * @param localShapeBounds the neighbor's collision-shape bounds in its local 0..1 frame
     * @param neighborCenter   the neighbor's exact world-space model center
     * @param hitPoint         the world-space point where the ray hit the neighbor's face
     * @param playerFacing     the player's horizontal facing (world)
     * @return the slab's world-space box center and the placement target direction
     */
    public static Result place(SlabPlacement.Mode mode, Quaternionf rotation, AABB localShapeBounds,
                               Vec3 neighborCenter, Vec3 hitPoint, Direction playerFacing) {
        LocalClick click = localClick(rotation, localShapeBounds, neighborCenter, hitPoint, playerFacing);
        // The placement target in the LOCAL frame - the same region rules normal blocks use.
        Direction target = rawTarget(mode, rotation, localShapeBounds, neighborCenter, hitPoint, playerFacing);
        // CENTER of a side face: a vertical slab attached flat against the face. The slab's box
        // occupies the half toward the block (+DIRECTION half), so the direction points at the
        // block: the local face normal.
        if (target == click.face()) {
            target = click.face().getOpposite();
        }
        // World geometry: the landing box's face sits on the clicked face plane, the box center
        // half a block off it, oriented with the block.
        double planeOffset = planeOffset(click.face(), localShapeBounds);
        Vector3f n = rotation.transform(new Vector3f(
                click.face().getStepX(), click.face().getStepY(), click.face().getStepZ()), new Vector3f());
        Vec3 facePlane = neighborCenter.add(n.x * planeOffset, n.y * planeOffset, n.z * planeOffset);
        Vec3 boxCenter = facePlane.add(n.x * 0.5, n.y * 0.5, n.z * 0.5);
        return new Result(boxCenter, target);
    }

    /**
     * The raw region target (before the CENTER flip) for a click against a rotated block, in the
     * neighbor's LOCAL frame - the same direction the vanilla region rules would pick. CENTER of
     * a vertical face yields the face itself; {@link #place} turns that into the attached
     * vertical slab by flipping it to the inward normal.
     */
    public static Direction rawTarget(SlabPlacement.Mode mode, Quaternionf rotation, AABB localShapeBounds,
                                      Vec3 neighborCenter, Vec3 hitPoint, Direction playerFacing) {
        LocalClick click = localClick(rotation, localShapeBounds, neighborCenter, hitPoint, playerFacing);
        return SlabPlacement.getTargetedDirection(mode, click.face(), click.localFacing(),
                BlockPos.ZERO, click.localHit());
    }

    /**
     * The click state in the neighbor's LOCAL frame: the local face the ray hit, the hit point
     * in local 0..1 coordinates and the player's facing snapped to a local cardinal. All three
     * are derived from the same inverse rotation, so the overlay and the placement always agree.
     */
    public static LocalClick localClick(Quaternionf rotation, AABB localShapeBounds, Vec3 neighborCenter,
                                        Vec3 hitPoint, Direction playerFacing) {
        Quaternionf inverse = new Quaternionf(rotation).conjugate();
        // The local face the ray hit: the shape-bounds plane closest to the hit point. This is
        // exact for full blocks (and half-blocks), so diagonal rotations resolve correctly
        // instead of snapping to the wrong cardinal.
        Direction localFace = hitFace(rotation, localShapeBounds, neighborCenter, hitPoint);
        // The hit point in the neighbor's local 0..1 frame (centered at 0.5).
        Vector3f rel = inverse.transform(new Vector3f(
                (float) (hitPoint.x - neighborCenter.x),
                (float) (hitPoint.y - neighborCenter.y),
                (float) (hitPoint.z - neighborCenter.z)), new Vector3f());
        Vec3 localHit = new Vec3(rel.x + 0.5, rel.y + 0.5, rel.z + 0.5);
        // The player's facing in the neighbor's local frame (cardinal).
        Vector3f f = inverse.transform(new Vector3f(
                playerFacing.getStepX(), playerFacing.getStepY(), playerFacing.getStepZ()), new Vector3f());
        return new LocalClick(localFace, localHit, snap(f));
    }

    /** The click geometry in the neighbor's local frame (see {@link #localClick}). */
    public record LocalClick(Direction face, Vec3 localHit, Direction localFacing) {
    }

    /** The local cardinal direction of the shape-bounds plane closest to the hit point. */
    private static Direction hitFace(Quaternionf rotation, AABB bounds, Vec3 center, Vec3 hitPoint) {
        Direction[] faces = {
                Direction.WEST, Direction.EAST, Direction.DOWN,
                Direction.UP, Direction.NORTH, Direction.SOUTH
        };
        // The signed distance of each face plane from the block center along the face's OWN
        // outward normal: positive-axis faces sit at max* - 0.5, negative-axis faces at
        // 0.5 - min* (the plane is at min*, the center at 0.5, the normal points away).
        double[] offsets = {
                0.5 - bounds.minX, bounds.maxX - 0.5, 0.5 - bounds.minY,
                bounds.maxY - 0.5, 0.5 - bounds.minZ, bounds.maxZ - 0.5
        };
        double best = 0.3;
        Direction bestFace = null;
        for (int i = 0; i < faces.length; i++) {
            Direction face = faces[i];
            Vector3f n = rotation.transform(new Vector3f(
                    face.getStepX(), face.getStepY(), face.getStepZ()), new Vector3f());
            double d = Math.abs((hitPoint.x - center.x) * n.x
                    + (hitPoint.y - center.y) * n.y
                    + (hitPoint.z - center.z) * n.z - offsets[i]);
            if (d < best) {
                best = d;
                bestFace = face;
            }
        }
        return bestFace != null ? bestFace : Direction.UP;
    }

    /** The signed distance of the face's plane from the block center, in local units, along the
     *  face's own outward normal (positive for EAST/UP/SOUTH, negative for WEST/DOWN/NORTH). */
    private static double planeOffset(Direction face, AABB bounds) {
        return switch (face) {
            case WEST -> 0.5 - bounds.minX;
            case EAST -> bounds.maxX - 0.5;
            case DOWN -> 0.5 - bounds.minY;
            case UP -> bounds.maxY - 0.5;
            case NORTH -> 0.5 - bounds.minZ;
            case SOUTH -> bounds.maxZ - 0.5;
        };
    }

    /** The nearest cardinal direction to an arbitrary vector. */
    private static Direction snap(Vector3f v) {
        float ax = Math.abs(v.x);
        float ay = Math.abs(v.y);
        float az = Math.abs(v.z);
        if (ax >= ay && ax >= az) {
            return v.x >= 0.0F ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return v.y >= 0.0F ? Direction.UP : Direction.DOWN;
        }
        return v.z >= 0.0F ? Direction.SOUTH : Direction.NORTH;
    }
}
