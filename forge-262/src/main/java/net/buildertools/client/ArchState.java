package net.buildertools.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.HashSet;
import java.util.Set;

/**
 * Client-side state for the Arching mechanic (hold ALT+A with a block in hand):
 * <ol>
 *   <li>while ALT+A is held, the client records every vanilla block the player places
 *       ({@link #recordPlacement});</li>
 *   <li>pressing LMB starts a stretch drag on the recorded region's nearest face - moving the
 *       mouse stretches the row exactly like the Selection tool's Alt+drag
 *       ({@link #beginDrag}/{@link #updateDrag}/{@link #finishDrag});</li>
 *   <li>after the drag is released, the next LMB click on a block to the side of the row sends
 *       the arch request ({@code ArchPacket}) with the stretched region and the clicked cell.</li>
 * </ol>
 * The region (the row) is a plain inclusive min/max box; it is the union of the recorded
 * placements and is updated to the post-stretch bounds when a drag commits.
 */
@OnlyIn(Dist.CLIENT)
public final class ArchState {
    public enum Phase {
        /** Waiting for the player to place blocks and start the stretch drag. */
        PLACING,
        /** LMB is held and a stretch drag is in progress. */
        DRAGGING,
        /** The drag ended; the next LMB click on a block arches the row. */
        AWAIT_ARCH
    }

    /** The outcome of a finished drag, for the caller to send as a {@code StretchPacket}. */
    public record DragResult(int axis, boolean positive, boolean moved,
                             BlockPos origMin, BlockPos origMax,
                             BlockPos newMin, BlockPos newMax) {
    }

    private static final int WORLD_LIMIT = 30_000_000;

    private static boolean active;
    private static Phase phase = Phase.PLACING;
    private static final Set<BlockPos> placed = new HashSet<>();
    private static BlockPos min;
    private static BlockPos max;

    // Stretch drag state (mirrors HandleDragState: a plane fixed at grab time, the dragged face
    // tracks the mouse ray against it, snapped to the block grid).
    private static Direction.Axis dragAxis;
    private static boolean dragPositive;
    private static Vec3 planePoint;
    private static Vec3 planeNormal;
    private static Vec3 axisDir;
    private static BlockPos dragStartMin;
    private static BlockPos dragStartMax;
    private static boolean dragMoved;

    private ArchState() {
    }

    public static boolean isActive() {
        return active;
    }

    public static Phase phase() {
        return phase;
    }

    /** Enters arch mode (ALT+A held with a block in hand). */
    public static void begin() {
        if (active) {
            return;
        }
        active = true;
        phase = Phase.PLACING;
        placed.clear();
        min = null;
        max = null;
    }

    /** Leaves arch mode (ALT+A released, or the held item changed). Resets everything. */
    public static void end() {
        active = false;
        phase = Phase.PLACING;
        dragAxis = null;
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

    /** Records a block the player placed while in arch mode and grows the region. */
    public static void recordPlacement(BlockPos pos) {
        BlockPos p = pos.immutable();
        if (placed.add(p)) {
            min = min == null ? p : new BlockPos(
                    Math.min(min.getX(), p.getX()), Math.min(min.getY(), p.getY()), Math.min(min.getZ(), p.getZ()));
            max = max == null ? p : new BlockPos(
                    Math.max(max.getX(), p.getX()), Math.max(max.getY(), p.getY()), Math.max(max.getZ(), p.getZ()));
        }
    }

    /** Replaces the region with the post-stretch bounds (called when a stretch drag commits). */
    public static void updateRegion(BlockPos newMin, BlockPos newMax) {
        min = newMin.immutable();
        max = newMax.immutable();
        placed.clear();
    }

    /** Clears the recorded row after a successful arch; stays in arch mode for the next row. */
    public static void completeArch() {
        placed.clear();
        min = null;
        max = null;
        phase = Phase.PLACING;
    }

    /**
     * Starts a stretch drag on the region: grabs the region box face the cursor ray hits first
     * (or the largest-extent face facing the player when looking away) and fixes a drag plane
     * through the grab point perpendicular to the view. Returns false when there is no region yet.
     */
    public static boolean beginDrag(Vec3 eye, Vec3 look) {
        if (!hasRegion() || dragAxis != null) {
            return false;
        }
        AABB box = regionBox();
        Vec3 dir = look.normalize();
        double bestT = Double.POSITIVE_INFINITY;
        Direction.Axis bestAxis = null;
        boolean bestPositive = false;
        Vec3 bestPoint = null;

        // The axis of the row is the natural stretch axis when the ray misses the box.
        int ex = max.getX() - min.getX();
        int ey = max.getY() - min.getY();
        int ez = max.getZ() - min.getZ();
        int fallbackAxis = ex >= ey && ex >= ez ? 0 : ey >= ez ? 1 : 2;

        double[] lo = {box.minX, box.minY, box.minZ};
        double[] hi = {box.maxX, box.maxY, box.maxZ};
        double[] o = {eye.x, eye.y, eye.z};
        double[] d = {dir.x, dir.y, dir.z};
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                double plane = side == 0 ? lo[axis] : hi[axis];
                if (Math.abs(d[axis]) < 1.0E-8) {
                    continue;
                }
                double t = (plane - o[axis]) / d[axis];
                if (t < 0) {
                    continue;
                }
                Vec3 p = eye.add(dir.scale(t));
                int u = (axis + 1) % 3;
                int v = (axis + 2) % 3;
                double pu = u == 0 ? p.x : u == 1 ? p.y : p.z;
                double pv = v == 0 ? p.x : v == 1 ? p.y : p.z;
                if (pu < lo[u] - 1.0E-4 || pu > hi[u] + 1.0E-4
                        || pv < lo[v] - 1.0E-4 || pv > hi[v] + 1.0E-4) {
                    continue;
                }
                if (t < bestT) {
                    bestT = t;
                    bestAxis = Direction.Axis.values()[axis];
                    bestPositive = side == 1;
                    bestPoint = p;
                }
            }
        }
        if (bestAxis == null) {
            bestAxis = Direction.Axis.values()[fallbackAxis];
            bestPositive = d[fallbackAxis] > 0;
            Vec3 center = box.getCenter();
            bestPoint = new Vec3(
                    bestAxis == Direction.Axis.X ? center.x : eye.x,
                    bestAxis == Direction.Axis.Y ? center.y : eye.y,
                    bestAxis == Direction.Axis.Z ? center.z : eye.z);
        }

        dragAxis = bestAxis;
        dragPositive = bestPositive;
        planePoint = bestPoint;
        planeNormal = dir;
        axisDir = switch (bestAxis) {
            case X -> new Vec3(1, 0, 0);
            case Y -> new Vec3(0, 1, 0);
            case Z -> new Vec3(0, 0, 1);
        };
        dragStartMin = min.immutable();
        dragStartMax = max.immutable();
        dragMoved = false;
        phase = Phase.DRAGGING;
        return true;
    }

    public static boolean isDragging() {
        return dragAxis != null;
    }

    /** The current position of the dragged face (to detect whether the drag moved). */
    private static int currentFace() {
        return switch (dragAxis) {
            case X -> dragPositive ? max.getX() : min.getX();
            case Y -> dragPositive ? max.getY() : min.getY();
            case Z -> dragPositive ? max.getZ() : min.getZ();
        };
    }

    /** Moves the dragged face so it follows the mouse ray against the drag plane (per tick). */
    public static void updateDrag(Vec3 eye, Vec3 look) {
        if (dragAxis == null || dragStartMin == null || dragStartMax == null) {
            dragAxis = null;
            return;
        }
        double denom = look.dot(planeNormal);
        if (Math.abs(denom) < 1.0E-5) {
            return;
        }
        Vec3 toPlane = planePoint.subtract(eye);
        double t = toPlane.dot(planeNormal) / denom;
        if (t < 0) {
            return;
        }
        Vec3 p = eye.add(look.scale(t));
        int block = (int) Math.floor(p.dot(axisDir) + 0.5);
        block = Math.max(-WORLD_LIMIT, Math.min(WORLD_LIMIT, block));
        if (block != currentFace()) {
            dragMoved = true;
        }
        applyFace(block);
    }

    private static void applyFace(int block) {
        BlockPos c1;
        BlockPos c2;
        switch (dragAxis) {
            case X -> {
                int lo = dragPositive ? Math.min(dragStartMin.getX(), block) : Math.min(block, dragStartMax.getX());
                int hi = dragPositive ? Math.max(dragStartMin.getX(), block) : Math.max(block, dragStartMax.getX());
                c1 = new BlockPos(lo, dragStartMin.getY(), dragStartMin.getZ());
                c2 = new BlockPos(hi, dragStartMax.getY(), dragStartMax.getZ());
            }
            case Y -> {
                int lo = dragPositive ? Math.min(dragStartMin.getY(), block) : Math.min(block, dragStartMax.getY());
                int hi = dragPositive ? Math.max(dragStartMin.getY(), block) : Math.max(block, dragStartMax.getY());
                c1 = new BlockPos(dragStartMin.getX(), lo, dragStartMin.getZ());
                c2 = new BlockPos(dragStartMax.getX(), hi, dragStartMax.getZ());
            }
            default -> {
                int lo = dragPositive ? Math.min(dragStartMin.getZ(), block) : Math.min(block, dragStartMax.getZ());
                int hi = dragPositive ? Math.max(dragStartMin.getZ(), block) : Math.max(block, dragStartMax.getZ());
                c1 = new BlockPos(dragStartMin.getX(), dragStartMin.getY(), lo);
                c2 = new BlockPos(dragStartMax.getX(), dragStartMax.getY(), hi);
            }
        }
        min = new BlockPos(Math.min(c1.getX(), c2.getX()), Math.min(c1.getY(), c2.getY()), Math.min(c1.getZ(), c2.getZ()));
        max = new BlockPos(Math.max(c1.getX(), c2.getX()), Math.max(c1.getY(), c2.getY()), Math.max(c1.getZ(), c2.getZ()));
    }

    /**
     * Ends the drag. When the face actually moved, the caller sends the stretch (the returned
     * result carries the original and new regions plus the dragged axis) and the phase moves to
     * {@link Phase#AWAIT_ARCH} so the next LMB click arches the row; a click that never dragged
     * simply returns to placing.
     */
    public static DragResult finishDrag() {
        if (dragAxis == null) {
            return null;
        }
        Direction.Axis axis = dragAxis;
        boolean positive = dragPositive;
        BlockPos origMin = dragStartMin;
        BlockPos origMax = dragStartMax;
        boolean moved = dragMoved;
        dragAxis = null;
        if (moved) {
            phase = Phase.AWAIT_ARCH;
            return new DragResult(axis.ordinal(), positive, true, origMin, origMax,
                    min != null ? min : origMin, max != null ? max : origMax);
        }
        phase = Phase.PLACING;
        return new DragResult(axis.ordinal(), positive, false, origMin, origMax, origMin, origMax);
    }
}
