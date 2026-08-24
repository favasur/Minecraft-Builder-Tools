package net.buildertools.util;

import com.mojang.math.Transformation;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Shared math for off-grid blocks. A {@link net.minecraft.world.entity.Display.BlockDisplay}
 * renders its block model spanning 0..1 from the entity's position, so the entity is spawned at
 * the cell corner and the transformation rotates the model around the cell center
 * (0.5, 0.5, 0.5). The block therefore sits exactly on the vanilla grid while it spins in place,
 * Hytale-style. Yaw turns around the world Y axis (horizontal), pitch around the model's X axis
 * (vertical tilt).
 */
public final class OffGridTransform {
    public static final float HALF = 0.5f;

    private OffGridTransform() {
    }

    /**
     * Rotation applied to the model: yaw around Y (world), then pitch around X. The yaw is
     * negated to match the client's cursor-angle convention (0 at +X, counter-clockwise).
     */
    public static Quaternionf rotation(float yawDeg, float pitchDeg) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDeg))
                .mul(new Quaternionf().rotateX((float) Math.toRadians(pitchDeg)));
    }

    /**
     * The display transformation for a block whose entity sits at the cell corner: M(p) = R·p + t
     * with t = c - R·c and c = (0.5, 0.5, 0.5), so model points p in 0..1 land exactly on the
     * cell and the cube rotates around its own center.
     */
    public static Transformation transformation(float yawDeg, float pitchDeg) {
        Quaternionf rot = rotation(yawDeg, pitchDeg);
        Vector3f center = new Vector3f(HALF, HALF, HALF);
        Vector3f rotCenter = rot.transform(center, new Vector3f());
        Vector3f translation = new Vector3f(center).sub(rotCenter);
        return new Transformation(translation, rot, new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf());
    }

    /**
     * True when the ACTUAL rotated models of two off-grid blocks overlap (penetrate), not merely
     * touch. Each model is judged by its collision shape's OWN cells (a stair's step and riser, a
     * fence's posts and rails - NOT the bounding-box envelope), so the empty parts of a shape
     * never count as solid: a stair placed flush under another stair's overhang is allowed, while
     * a block pushed INTO another is rejected. Any solid cell of one model touching any cell of
     * the other counts as overlap - the Separating Axis Theorem runs on every cell pair, so the
     * face-to-face tolerance still lets flush placements pass.
     */
    public static boolean modelsOverlap(double cx1, double cy1, double cz1, float yaw1, float pitch1, VoxelShape shape1,
                                        double cx2, double cy2, double cz2, float yaw2, float pitch2, VoxelShape shape2) {
        if (shape1.isEmpty() || shape2.isEmpty()) {
            return false;
        }
        // The models' own discrete cells, collected once per call. Each cell is an oriented box in
        // the model's local frame, so the SAT test works unchanged on cell pairs.
        java.util.List<AABB> cells1 = new java.util.ArrayList<>();
        shape1.forAllBoxes((x0, y0, z0, x1, y1, z1) -> cells1.add(new AABB(x0, y0, z0, x1, y1, z1)));
        java.util.List<AABB> cells2 = new java.util.ArrayList<>();
        shape2.forAllBoxes((x0, y0, z0, x1, y1, z1) -> cells2.add(new AABB(x0, y0, z0, x1, y1, z1)));
        for (AABB cell1 : cells1) {
            for (AABB cell2 : cells2) {
                if (orientedBoxesOverlap(cx1, cy1, cz1, yaw1, pitch1, cell1,
                        cx2, cy2, cz2, yaw2, pitch2, cell2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * SAT overlap test for a single pair of oriented boxes (one cell of each model). Two blocks
     * placed flush against each other (their models touching face-to-face) are allowed, while a
     * block pushed INTO another is rejected. The axis-aligned bounding box cannot be used here - a
     * rotated cube's AABB inflates at the corners, so flush-adjacent rotated blocks would always
     * look overlapping even though the models just touch.
     */
    private static boolean orientedBoxesOverlap(double cx1, double cy1, double cz1, float yaw1, float pitch1, AABB shape1,
                                                double cx2, double cy2, double cz2, float yaw2, float pitch2, AABB shape2) {
        Quaternionf rot1 = rotation(yaw1, pitch1);
        Quaternionf rot2 = rotation(yaw2, pitch2);
        // Local (rotated) axes of each model.
        Vector3f a1x = rot1.transform(new Vector3f(1, 0, 0), new Vector3f());
        Vector3f a1y = rot1.transform(new Vector3f(0, 1, 0), new Vector3f());
        Vector3f a1z = rot1.transform(new Vector3f(0, 0, 1), new Vector3f());
        Vector3f a2x = rot2.transform(new Vector3f(1, 0, 0), new Vector3f());
        Vector3f a2y = rot2.transform(new Vector3f(0, 1, 0), new Vector3f());
        Vector3f a2z = rot2.transform(new Vector3f(0, 0, 1), new Vector3f());
        // Half-extents along each model's own axes.
        float h1x = (float) ((shape1.maxX - shape1.minX) / 2.0);
        float h1y = (float) ((shape1.maxY - shape1.minY) / 2.0);
        float h1z = (float) ((shape1.maxZ - shape1.minZ) / 2.0);
        float h2x = (float) ((shape2.maxX - shape2.minX) / 2.0);
        float h2y = (float) ((shape2.maxY - shape2.minY) / 2.0);
        float h2z = (float) ((shape2.maxZ - shape2.minZ) / 2.0);
        Vector3f delta = new Vector3f((float) (cx2 - cx1), (float) (cy2 - cy1), (float) (cz2 - cz1));

        // The 15 candidate separating axes: the 3 axes of each box plus their 9 cross products.
        Vector3f[] axes = {
                a1x, a1y, a1z, a2x, a2y, a2z,
                a1x.cross(a2x, new Vector3f()), a1x.cross(a2y, new Vector3f()), a1x.cross(a2z, new Vector3f()),
                a1y.cross(a2x, new Vector3f()), a1y.cross(a2y, new Vector3f()), a1y.cross(a2z, new Vector3f()),
                a1z.cross(a2x, new Vector3f()), a1z.cross(a2y, new Vector3f()), a1z.cross(a2z, new Vector3f())
        };
        for (Vector3f axis : axes) {
            double len = axis.length();
            if (len < 1.0E-5) {
                continue; // parallel axes: the cross product is degenerate, no separating power
            }
            Vector3f n = new Vector3f(axis).div((float) len);
            double r1 = h1x * Math.abs(a1x.dot(n)) + h1y * Math.abs(a1y.dot(n)) + h1z * Math.abs(a1z.dot(n));
            double r2 = h2x * Math.abs(a2x.dot(n)) + h2y * Math.abs(a2y.dot(n)) + h2z * Math.abs(a2z.dot(n));
            double dist = Math.abs(delta.dot(n));
            // A separating axis exists when the projected intervals are disjoint; a small
            // tolerance makes face-to-face touching count as clear, so flush placements pass.
            if (dist > r1 + r2 - 1.0E-3) {
                return false;
            }
        }
        return true;
    }

    /**
     * The world-space AABB of a block shape (in block-local 0..1 coordinates, as returned by
     * {@code BlockState#getCollisionShape}) rotated by the placement yaw/pitch around the model
     * center {@code (cx, cy, cz)}. This is the tight axis-aligned box that encloses the rotated
     * model - the same box the rendered display spans - so collision and visuals can never drift
     * apart. (Legacy entity path; the real-block rotation uses {@link #rotatedShape}.)
     */
    public static AABB boxAround(double cx, double cy, double cz, float yawDeg, float pitchDeg, AABB shape) {
        Quaternionf rot = rotation(yawDeg, pitchDeg);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{shape.minX, shape.maxX}) {
            for (double y : new double[]{shape.minY, shape.maxY}) {
                for (double z : new double[]{shape.minZ, shape.maxZ}) {
                    Vector3f p = rot.transform(
                            new Vector3f((float) (x - HALF), (float) (y - HALF), (float) (z - HALF)),
                            new Vector3f());
                    minX = Math.min(minX, cx + p.x);
                    minY = Math.min(minY, cy + p.y);
                    minZ = Math.min(minZ, cz + p.z);
                    maxX = Math.max(maxX, cx + p.x);
                    maxY = Math.max(maxY, cy + p.y);
                    maxZ = Math.max(maxZ, cz + p.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // ------------------------------------------------------------------
    // Rendered-model base shape (client hook)
    // ------------------------------------------------------------------

    /** Client-registered source of a block's rendered-model shape (see ModelShapeProvider). */
    private static java.util.function.Function<BlockState, VoxelShape> MODEL_SHAPE_PROVIDER;

    /** Registers the client-side provider that maps a block state to its rendered-model shape. */
    public static void setModelShapeProvider(java.util.function.Function<BlockState, VoxelShape> provider) {
        MODEL_SHAPE_PROVIDER = provider;
    }

    /**
     * The block's RENDERED model as a base collision shape, or null when unavailable (no client,
     * custom renderer, non-reconstructable geometry). Callers fall back to the collision shape,
     * so the voxelized hitbox follows the visible model (stair notches, thin fence posts)
     * wherever it can, and stays correct everywhere else.
     */
    public static VoxelShape modelShape(BlockState state) {
        java.util.function.Function<BlockState, VoxelShape> provider = MODEL_SHAPE_PROVIDER;
        if (provider != null) {
            try {
                VoxelShape model = provider.apply(state);
                if (model != null && !model.isEmpty()) {
                    return model;
                }
            } catch (Throwable ignored) {
                // Best-effort: any failure falls back to the collision shape.
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Real-block rotated collision: a voxelized approximation of the rotated
    // model so the hitbox matches the rotated render (Minecraft collision can
    // only be axis-aligned boxes, so the rotated cube's flat faces are stepped
    // into a fine grid of thin boxes - the same "pixels" idea, just for the
    // hitbox). The grid is filled directly (no per-voxel shape unions), so a
    // shape costs microseconds, not seconds - the hitbox can never lag behind
    // the render, and the player stops exactly at the visible rotated faces.
    // ------------------------------------------------------------------

    private static final java.util.Map<ShapeKey, VoxelShape> SHAPE_CACHE = new java.util.HashMap<>();
    private static final int MAX_CACHE_ENTRIES = 4096;
    /** Voxels per block unit; finer = smoother collision against the rotated faces. */
    private static final int GRID = 16;

    private record ShapeKey(BlockState state, float yaw, float pitch, float cx, float cy, float cz) {
    }

    /**
     * The cell-local collision shape (centered on 0.5, 0.5, 0.5) of a real block rotated by the
     * placement yaw/pitch: the base shape's own collision cells (the actual stair steps, fence
     * posts and rails - NOT the bounding-box envelope), sampled over the FULL rotated extent (the
     * parts that stick out of the cell included) and stepped into a {@value GRID}-per-unit voxel
     * grid, so the hitbox matches the rotated render - including the corners that protrude past
     * the cell. Identity rotation returns the base shape unchanged;
     * results are cached per (block state, yaw, pitch) with the angles quantized to whole degrees
     * so drag rotations share entries. Callers move the result to the block's cell position.
     */
    public static VoxelShape rotatedShape(BlockState state, VoxelShape base, float yawDeg, float pitchDeg) {
        return rotatedShape(state, base, yawDeg, pitchDeg, HALF, HALF, HALF);
    }

    /**
     * Same as {@link #rotatedShape(BlockState, VoxelShape, float, float)} but the model rotates
     * around an arbitrary world-space center {@code (cx, cy, cz)} and the returned shape is
     * already world-positioned. Used for the legacy entity blocks, whose model center can be
     * fractional (flush-adjacent rotated strata), so their collision stays glued to the visual.
     */
    public static VoxelShape rotatedShape(BlockState state, VoxelShape base, float yawDeg, float pitchDeg,
                                          double cx, double cy, double cz) {
        if (base.isEmpty()) {
            return Shapes.empty();
        }
        if (yawDeg == 0.0f && pitchDeg == 0.0f) {
            // Unrotated: the base shape is cell-local (0..1); park it at the model center.
            return base.move(cx - HALF, cy - HALF, cz - HALF);
        }
        // Quantize the cache key: the shape barely changes under a fraction of a degree, and
        // whole-degree steps + coarse center quantization keep the cache tiny.
        float qyaw = Math.round(yawDeg);
        float qpitch = Math.round(pitchDeg);
        float qcx = Math.round((float) cx * 8.0f) / 8.0f;
        float qcy = Math.round((float) cy * 8.0f) / 8.0f;
        float qcz = Math.round((float) cz * 8.0f) / 8.0f;
        ShapeKey key = new ShapeKey(state, qyaw, qpitch, qcx, qcy, qcz);
        VoxelShape cached = SHAPE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        AABB bounds = base.bounds();
        // The full rotated extent around the model center, in the same coordinate frame as the
        // center (cell-local for the cell center, world for the legacy entities) - includes the
        // parts of the rotated model that stick out of the cell, so the collision reaches exactly
        // where the rendered block is.
        AABB extent = boxAround(cx, cy, cz, yawDeg, pitchDeg, bounds);
        int nx = Math.max(1, (int) Math.ceil((extent.maxX - extent.minX) * GRID));
        int ny = Math.max(1, (int) Math.ceil((extent.maxY - extent.minY) * GRID));
        int nz = Math.max(1, (int) Math.ceil((extent.maxZ - extent.minZ) * GRID));
        double dx = (extent.maxX - extent.minX) / nx;
        double dy = (extent.maxY - extent.minY) / ny;
        double dz = (extent.maxZ - extent.minZ) / nz;
        // The base shape's OWN discrete cells, not its bounding-box envelope: a stair's step and
        // riser, a fence's posts and rails, a slab's half-block. A voxel center must land inside
        // one of these cells to mark the voxel solid, so the rotated hitbox follows the block's
        // real shape instead of filling in the empty parts (the notch under a stair, the gaps
        // between fence rails). The cells tile the shape's filled region exactly, so the test is
        // equivalent to a point-in-shape test on the collision geometry itself.
        java.util.List<AABB> cells = new java.util.ArrayList<>();
        base.forAllBoxes((x0, y0, z0, x1, y1, z1) -> cells.add(new AABB(x0, y0, z0, x1, y1, z1)));
        Quaternionf inv = new Quaternionf(rotation(yawDeg, pitchDeg)).conjugate();
        // Fill the occupancy grid directly: each cell of the {GRID}-per-unit lattice is marked
        // solid when its center lands inside the rotated model. One pass, no shape unions.
        BitSetDiscreteVoxelShape grid = new BitSetDiscreteVoxelShape(nx, ny, nz);
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    // Voxel center; map into the model's own space by the inverse rotation around
                    // the model center, then test it against the base shape's own cells. The
                    // model's local frame spans 0..1 centered at 0.5, so the inverse-rotated
                    // point must land in [0,1] - the world center c is NOT the local center
                    // (that's what makes this work for legacy blocks with fractional world
                    // centers).
                    double wx = extent.minX + (i + 0.5) * dx;
                    double wy = extent.minY + (j + 0.5) * dy;
                    double wz = extent.minZ + (k + 0.5) * dz;
                    Vector3f v = inv.transform(new Vector3f(
                            (float) (wx - cx), (float) (wy - cy), (float) (wz - cz)), new Vector3f());
                    if (insideAny(cells, v.x + HALF, v.y + HALF, v.z + HALF)) {
                        grid.fill(i, j, k);
                    }
                }
            }
        }
        // The grid's own coordinate axes are the identity 0..1; the rotated extent starts at
        // extent.min, so the shape carries the exact extent edges as its coordinates.
        DoubleList xs = new DoubleArrayList(nx + 1);
        DoubleList ys = new DoubleArrayList(ny + 1);
        DoubleList zs = new DoubleArrayList(nz + 1);
        for (int i = 0; i <= nx; i++) {
            xs.add(extent.minX + i * dx);
        }
        for (int j = 0; j <= ny; j++) {
            ys.add(extent.minY + j * dy);
        }
        for (int k = 0; k <= nz; k++) {
            zs.add(extent.minZ + k * dz);
        }
        VoxelShape result = new DirectVoxelShape(grid, xs, ys, zs);
        if (SHAPE_CACHE.size() >= MAX_CACHE_ENTRIES) {
            SHAPE_CACHE.clear();
        }
        SHAPE_CACHE.put(key, result);
        return result;
    }

    /**
     * The world-space offset from a rotated block's center to the center of a NEW block placed
     * flush against the face the ray enters: ONE unit along the clicked local axis, rotated into
     * world space by the block's own rotation. Clicking the same face again keeps building the
     * rotated plane (PlaceAnywhere-style), because every new block inherits the rotation and its
     * center lands exactly one rotated unit past the previous one. Returns null when the ray
     * misses the block's rotated model.
     */
    public static Vec3 rotatedGridOffset(Quaternionf rot, Vec3 center, AABB shape,
                                         Vec3 eye, Vec3 dir) {
        Quaternionf inv = new Quaternionf(rot).conjugate();
        // Ray in the block's local frame (the model is an axis-aligned box around the center).
        Vector3f o = inv.transform(new Vector3f(
                (float) (eye.x - center.x), (float) (eye.y - center.y), (float) (eye.z - center.z)),
                new Vector3f());
        Vector3f d = inv.transform(new Vector3f((float) dir.x, (float) dir.y, (float) dir.z),
                new Vector3f());
        double minX = shape.minX - HALF, maxX = shape.maxX - HALF;
        double minY = shape.minY - HALF, maxY = shape.maxY - HALF;
        double minZ = shape.minZ - HALF, maxZ = shape.maxZ - HALF;

        // Slab method, tracking the entry face (low or high plane per axis).
        double tmin = 0.0, tmax = Double.POSITIVE_INFINITY;
        int entryAxis = -1;
        boolean entryLow = false;
        for (int i = 0; i < 3; i++) {
            double oi = i == 0 ? o.x : i == 1 ? o.y : o.z;
            double di = i == 0 ? d.x : i == 1 ? d.y : d.z;
            double lo = i == 0 ? minX : i == 1 ? minY : minZ;
            double hi = i == 0 ? maxX : i == 1 ? maxY : maxZ;
            if (Math.abs(di) < 1.0E-8) {
                if (oi < lo || oi > hi) {
                    return null;
                }
                continue;
            }
            double tLow = (lo - oi) / di;
            double tHigh = (hi - oi) / di;
            boolean lowIsEntry = tLow < tHigh;
            if (!lowIsEntry) {
                double tmp = tLow;
                tLow = tHigh;
                tHigh = tmp;
            }
            if (tLow > tmin) {
                tmin = tLow;
                entryAxis = i;
                entryLow = lowIsEntry;
            }
            if (tHigh < tmax) {
                tmax = tHigh;
            }
            if (tmin > tmax) {
                return null;
            }
        }
        if (entryAxis < 0) {
            return null;
        }
        Vector3f face = switch (entryAxis) {
            case 0 -> new Vector3f(entryLow ? -1 : 1, 0, 0);
            case 1 -> new Vector3f(0, entryLow ? -1 : 1, 0);
            default -> new Vector3f(0, 0, entryLow ? -1 : 1);
        };
        Vector3f world = rot.transform(face, new Vector3f());
        return new Vec3(world.x, world.y, world.z);
    }

    /**
     * True when the point lands inside any of the base shape's own cells. The cells tile the
     * shape's filled region exactly (each is one discrete voxel of the collision geometry), so
     * this is a point-in-shape test on the block's actual shape - only the bounding-box envelope
     * would also include the empty parts (stair notches, fence gaps).
     */
    private static boolean insideAny(java.util.List<AABB> cells, double x, double y, double z) {
        for (AABB cell : cells) {
            if (cell.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A {@link VoxelShape} backed by an occupancy grid whose axis coordinates are the exact
     * rotated-extent edges (the grid's own 0..1 axes would not span the protruding parts).
     */
    private static final class DirectVoxelShape extends VoxelShape {
        private final DoubleList xs;
        private final DoubleList ys;
        private final DoubleList zs;

        DirectVoxelShape(DiscreteVoxelShape grid, DoubleList xs, DoubleList ys, DoubleList zs) {
            super(grid);
            this.xs = xs;
            this.ys = ys;
            this.zs = zs;
        }

        @Override
        public DoubleList getCoords(net.minecraft.core.Direction.Axis axis) {
            return switch (axis) {
                case X -> xs;
                case Y -> ys;
                case Z -> zs;
            };
        }
    }
}
