package net.buildertools.client;

import net.buildertools.util.OffGridTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supplies the RENDERED model geometry of a block state as a {@link VoxelShape}, so the rotated
 * collision voxelization samples the model the player actually sees (stair notches, thin fence
 * posts and rails) instead of the coarser collision shape. The model's axis-aligned cube faces
 * are read from the baked model and reconstructed into boxes; when that is impossible (custom
 * renderers, rotated or non-box geometry, culled faces, dedicated server), {@code null} is
 * returned and the callers fall back to the block's collision shape.
 *
 * <p>Results are cached per block state for the session. The lookup can run on the integrated
 * server thread in single-player (movement checks), so the cache is concurrent and every model
 * access is fully guarded - any failure just means the collision shape is used.
 */
public final class ModelShapeProvider {
    private static final Map<BlockState, VoxelShape> CACHE = new ConcurrentHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final double SCALE = 512.0;

    static {
        // Register as the source of the base shape for the rotated-block collision voxelization.
        OffGridTransform.setModelShapeProvider(ModelShapeProvider::get);
    }

    private ModelShapeProvider() {
    }

    /** Loads the class (triggering the registration above) - called from client startup. */
    public static void ensureLoaded() {
    }

    /** The block's rendered model as a base shape, or null when unavailable. */
    public static VoxelShape get(BlockState state) {
        VoxelShape cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        try {
            VoxelShape built = build(state);
            if (built != null && !built.isEmpty()) {
                CACHE.put(state, built);
                return built;
            }
        } catch (Throwable ignored) {
            // Model lookup is best-effort - any failure falls back to the collision shape.
        }
        return null;
    }

    private static VoxelShape build(BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        BakedModel model = mc.getModelManager().getBlockModelShaper().getBlockModel(state);
        if (model == null || model.isCustomRenderer()) {
            return null;
        }
        // Faces without a cullface come from the null direction; faces with one from their
        // cullface direction. Together they cover the model's whole surface.
        List<BakedQuad> quads = new ArrayList<>();
        quads.addAll(model.getQuads(state, null, RANDOM));
        for (Direction d : Direction.values()) {
            quads.addAll(model.getQuads(state, d, RANDOM));
        }
        List<AABB> boxes = reconstructBoxes(quads);
        if (boxes == null || boxes.isEmpty()) {
            return null;
        }
        VoxelShape shape = Shapes.empty();
        for (AABB box : boxes) {
            shape = Shapes.joinUnoptimized(shape,
                    Shapes.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
                    BooleanOp.OR);
        }
        return shape.isEmpty() ? null : shape;
    }

    /** An axis-aligned quad surface: constant coordinate on {@code axis}, rect on the other two
     *  axes, and the direction the face points along {@code axis} (+1 / -1). The sign keeps two
     *  coincident faces of adjacent elements distinct, so a shared plane still yields one
     *  interval per element. */
    private record Face(int axis, long value, long minU, long maxU, long minV, long maxV, int sign) {
    }

    /** A rect on the two non-constant axes. */
    private record Rect(long minU, long maxU, long minV, long maxV) {
    }

    /** A candidate interval on one axis: {@code min..max} with a rect on the other two axes. */
    private record Interval(double min, double max, double a0, double a1, double b0, double b1) {
    }

    /**
     * Reconstructs the model's axis-aligned boxes from its quads, or null when the model cannot
     * be represented exactly (non-axis-aligned faces, culled faces, gaps between elements).
     */
    private static List<AABB> reconstructBoxes(List<BakedQuad> quads) {
        List<Face> faces = new ArrayList<>();
        for (BakedQuad quad : quads) {
            Face face = faceOf(quad);
            if (face == null) {
                return null;
            }
            faces.add(face);
        }
        if (faces.isEmpty()) {
            return null;
        }
        // Coincident faces (same plane and rect, e.g. double-sided faces or element seams) are
        // one surface; dedupe before pairing.
        Set<Face> unique = new HashSet<>(faces);
        faces = new ArrayList<>(unique);

        // Per-axis interval candidates: for each rect on the other two axes, pair the sorted
        // plane values consecutively - each cube element spans between two consecutive faces.
        List<List<Interval>> intervals = new ArrayList<>(3);
        for (int axis = 0; axis < 3; axis++) {
            intervals.add(axisIntervals(faces, axis));
        }

        // Assemble boxes from every X-interval candidate, verifying the matching Y and Z
        // intervals exist so the three rects of a cube are mutually consistent.
        List<AABB> boxes = new ArrayList<>();
        for (Interval ix : intervals.get(0)) {
            double x0 = ix.min(), x1 = ix.max();
            double y0 = ix.a0(), y1 = ix.a1(), z0 = ix.b0(), z1 = ix.b1();
            // On the Y axis the rect is (Z, X); on the Z axis the rect is (X, Y).
            if (hasInterval(intervals.get(1), y0, y1, z0, z1, x0, x1)
                    && hasInterval(intervals.get(2), z0, z1, x0, x1, y0, y1)) {
                boxes.add(new AABB(x0, y0, z0, x1, y1, z1));
            }
        }
        if (boxes.isEmpty()) {
            return null;
        }

        // Completeness: every model face must lie on the boundary of a reconstructed box. A face
        // that fits no box means part of the model could not be reconstructed (e.g. a face was
        // culled between coplanar elements), so the whole reconstruction is discarded.
        Set<Face> covered = new HashSet<>();
        for (AABB b : boxes) {
            addFace(covered, 0, b.minX, b.minY, b.maxY, b.minZ, b.maxZ, -1);
            addFace(covered, 0, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ, 1);
            addFace(covered, 1, b.minY, b.minZ, b.maxZ, b.minX, b.maxX, -1);
            addFace(covered, 1, b.maxY, b.minZ, b.maxZ, b.minX, b.maxX, 1);
            addFace(covered, 2, b.minZ, b.minX, b.maxX, b.minY, b.maxY, -1);
            addFace(covered, 2, b.maxZ, b.minX, b.maxX, b.minY, b.maxY, 1);
        }
        for (Face face : faces) {
            if (!covered.contains(face)) {
                return null;
            }
        }
        return boxes;
    }

    private static void addFace(Set<Face> out, int axis, double value, double a0, double a1,
                                double b0, double b1, int sign) {
        out.add(new Face(axis, q(value), q(a0), q(a1), q(b0), q(b1), sign));
    }

    private static long q(double v) {
        return Math.round(v * SCALE);
    }

    /** The axis-aligned face of a quad, or null when the quad is not axis-aligned. */
    private static Face faceOf(BakedQuad quad) {
        int[] v = quad.getVertices();
        double[][] p = new double[4][3];
        for (int i = 0; i < 4; i++) {
            p[i][0] = Float.intBitsToFloat(v[i * 8]);
            p[i][1] = Float.intBitsToFloat(v[i * 8 + 1]);
            p[i][2] = Float.intBitsToFloat(v[i * 8 + 2]);
        }
        int axis = -1;
        double value = 0;
        for (int a = 0; a < 3; a++) {
            boolean same = true;
            for (int i = 1; i < 4; i++) {
                if (Math.abs(p[i][a] - p[0][a]) > 1.0E-4) {
                    same = false;
                    break;
                }
            }
            if (same) {
                axis = a;
                value = p[0][a];
                break;
            }
        }
        if (axis < 0) {
            return null;
        }
        int u = (axis + 1) % 3;
        int vv = (axis + 2) % 3;
        double minU = Math.min(Math.min(p[0][u], p[1][u]), Math.min(p[2][u], p[3][u]));
        double maxU = Math.max(Math.max(p[0][u], p[1][u]), Math.max(p[2][u], p[3][u]));
        double minV = Math.min(Math.min(p[0][vv], p[1][vv]), Math.min(p[2][vv], p[3][vv]));
        double maxV = Math.max(Math.max(p[0][vv], p[1][vv]), Math.max(p[2][vv], p[3][vv]));
        int sign = quad.getDirection().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
        return new Face(axis, q(value), q(minU), q(maxU), q(minV), q(maxV), sign);
    }

    private static List<Interval> axisIntervals(List<Face> faces, int axis) {
        Map<Rect, List<Long>> planesByRect = new HashMap<>();
        for (Face f : faces) {
            if (f.axis() != axis) {
                continue;
            }
            planesByRect.computeIfAbsent(new Rect(f.minU(), f.maxU(), f.minV(), f.maxV()),
                    k -> new ArrayList<>()).add(f.value());
        }
        List<Interval> out = new ArrayList<>();
        for (Map.Entry<Rect, List<Long>> e : planesByRect.entrySet()) {
            List<Long> planes = e.getValue();
            planes.sort(null);
            Rect r = e.getKey();
            for (int i = 0; i + 1 < planes.size(); i += 2) {
                out.add(new Interval(planes.get(i) / SCALE, planes.get(i + 1) / SCALE,
                        r.minU() / SCALE, r.maxU() / SCALE, r.minV() / SCALE, r.maxV() / SCALE));
            }
        }
        return out;
    }

    private static boolean hasInterval(List<Interval> intervals, double min, double max,
                                       double a0, double a1, double b0, double b1) {
        long qMin = q(min), qMax = q(max), qA0 = q(a0), qA1 = q(a1), qB0 = q(b0), qB1 = q(b1);
        for (Interval it : intervals) {
            if (q(it.min()) == qMin && q(it.max()) == qMax
                    && q(it.a0()) == qA0 && q(it.a1()) == qA1
                    && q(it.b0()) == qB0 && q(it.b1()) == qB1) {
                return true;
            }
        }
        return false;
    }
}
