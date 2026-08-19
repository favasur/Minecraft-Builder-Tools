package net.buildertools.client;

import net.buildertools.selection.SelectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side state while the player is dragging one of the selection face handles.
 *
 * <p>This ports the selection drag. Grabbing a face handle fixes a drag plane through the
 * grab point, perpendicular to the view direction at grab time. While the mouse is held, the
 * dragged face tracks the intersection of the mouse ray with that plane, projected onto the face
 * axis and snapped to the block grid. The face therefore follows the cursor smoothly even when
 * aiming at open air. The selection itself is kept as a
 * live min/max pair (matching the {@code BuilderToolSelectionUpdate} packet, which sends the
 * box as xMin/yMin/zMin/xMax/yMax/zMax).
 */
@OnlyIn(Dist.CLIENT)
public final class HandleDragState {
    private static Direction.Axis axis;
    private static boolean positive;
    private static Vec3 planePoint;
    private static Vec3 planeNormal;
    private static Vec3 axisDir;
    private static BlockPos startMin;
    private static BlockPos startMax;
    private static boolean hasMoved;
    /** Alt+drag: stretch the blocks inside the selection along the dragged axis on release. */
    private static boolean stretch;

    private static final int WORLD_LIMIT = 30_000_000;

    private HandleDragState() {
    }

    public static boolean isDragging() {
        return axis != null;
    }

    public static void start(Direction.Axis axis, boolean positive, Vec3 grabPoint, Vec3 viewDir) {
        start(axis, positive, grabPoint, viewDir, false);
    }

    public static void start(Direction.Axis axis, boolean positive, Vec3 grabPoint, Vec3 viewDir, boolean stretch) {
        HandleDragState.axis = axis;
        HandleDragState.positive = positive;
        HandleDragState.stretch = stretch;
        // The drag plane is fixed at grab time: through the grab point, perpendicular to the view.
        HandleDragState.planePoint = grabPoint;
        HandleDragState.planeNormal = viewDir.normalize();
        HandleDragState.axisDir = switch (axis) {
            case X -> new Vec3(1, 0, 0);
            case Y -> new Vec3(0, 1, 0);
            case Z -> new Vec3(0, 0, 1);
        };
        HandleDragState.startMin = SelectionManager.getMin();
        HandleDragState.startMax = SelectionManager.getMax();
        HandleDragState.hasMoved = false;
    }

    /**
     * Ends the drag. When the click finished without the face ever moving (a quick click rather
     * than a real drag), the face is nudged one block outward so a plain click still resizes the
     * region — the click-and-drag feel.
     */
    public static void stop(boolean finishedByRelease) {
        if (axis != null && finishedByRelease && !hasMoved && !stretch) {
            nudgeOneBlock();
        }
        axis = null;
        stretch = false;
    }

    public static Direction.Axis axis() {
        return axis;
    }

    public static boolean positive() {
        return positive;
    }

    public static boolean isStretch() {
        return stretch;
    }

    /** Whether the dragged face actually moved during this drag. */
    public static boolean hasMoved() {
        return hasMoved;
    }

    /** The region as it was when the drag started (before the face moved). */
    public static BlockPos origMin() {
        return startMin;
    }

    public static BlockPos origMax() {
        return startMax;
    }

    /**
     * Moves the dragged face so it follows the mouse ray, snapping to the block grid. Only the
     * grabbed face moves; the opposite face (and the other two axes) stay where they were at grab
     * time, and the selection is renormalised to min/max.
     */
    public static void update(Vec3 eye, Vec3 dir) {
        if (axis == null || startMin == null || startMax == null) {
            axis = null;
            return;
        }
        double denom = dir.dot(planeNormal);
        if (Math.abs(denom) < 1.0E-5) {
            return; // ray parallel to the drag plane: keep the last value
        }
        Vec3 toPlane = planePoint.subtract(eye);
        double t = toPlane.dot(planeNormal) / denom;
        if (t < 0) {
            return;
        }
        Vec3 p = eye.add(dir.scale(t));
        int block = (int) Math.floor(p.dot(axisDir) + 0.5);
        block = Math.max(-WORLD_LIMIT, Math.min(WORLD_LIMIT, block));

        // Remember whether the face actually moved, so a click that never dragged can still
        // resize the region by one block when the button is released.
        if (block != currentFace()) {
            hasMoved = true;
        }
        apply(block);
    }

    /** The block position of the dragged face right now (used to detect drag movement). */
    private static int currentFace() {
        BlockPos min = SelectionManager.getMin();
        BlockPos max = SelectionManager.getMax();
        return switch (axis) {
            case X -> positive ? max.getX() : min.getX();
            case Y -> positive ? max.getY() : min.getY();
            default -> positive ? max.getZ() : min.getZ();
        };
    }

    private static void apply(int block) {
        BlockPos c1;
        BlockPos c2;
        switch (axis) {
            case X -> {
                int lo = positive ? Math.min(startMin.getX(), block) : Math.min(block, startMax.getX());
                int hi = positive ? Math.max(startMin.getX(), block) : Math.max(block, startMax.getX());
                c1 = new BlockPos(lo, startMin.getY(), startMin.getZ());
                c2 = new BlockPos(hi, startMax.getY(), startMax.getZ());
            }
            case Y -> {
                int lo = positive ? Math.min(startMin.getY(), block) : Math.min(block, startMax.getY());
                int hi = positive ? Math.max(startMin.getY(), block) : Math.max(block, startMax.getY());
                c1 = new BlockPos(startMin.getX(), lo, startMin.getZ());
                c2 = new BlockPos(startMax.getX(), hi, startMax.getZ());
            }
            default -> {
                int lo = positive ? Math.min(startMin.getZ(), block) : Math.min(block, startMax.getZ());
                int hi = positive ? Math.max(startMin.getZ(), block) : Math.max(block, startMax.getZ());
                c1 = new BlockPos(startMin.getX(), startMin.getY(), lo);
                c2 = new BlockPos(startMax.getX(), startMax.getY(), hi);
            }
        }
        SelectionManager.setCorner1(c1);
        SelectionManager.setCorner2(c2);
    }

    /** Moves the grabbed face one block outward, for quick clicks that never turned into a drag. */
    private static void nudgeOneBlock() {
        int delta = positive ? 1 : -1;
        BlockPos c1;
        BlockPos c2;
        switch (axis) {
            case X -> {
                c1 = new BlockPos(startMin.getX() + (positive ? 0 : delta), startMin.getY(), startMin.getZ());
                c2 = new BlockPos(startMax.getX() + (positive ? delta : 0), startMax.getY(), startMax.getZ());
            }
            case Y -> {
                c1 = new BlockPos(startMin.getX(), startMin.getY() + (positive ? 0 : delta), startMin.getZ());
                c2 = new BlockPos(startMax.getX(), startMax.getY() + (positive ? delta : 0), startMax.getZ());
            }
            default -> {
                c1 = new BlockPos(startMin.getX(), startMin.getY(), startMin.getZ() + (positive ? 0 : delta));
                c2 = new BlockPos(startMax.getX(), startMax.getY(), startMax.getZ() + (positive ? delta : 0));
            }
        }
        SelectionManager.setCorner1(c1);
        SelectionManager.setCorner2(c2);
    }
}
