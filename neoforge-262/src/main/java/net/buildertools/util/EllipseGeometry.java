package net.buildertools.util;

import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The math behind the Ellipse building mechanic (ALT+E): a placed rectangle is re-shaped into a
 * complete, closed loop of tapered voussoirs following an ellipse. The centerline is
 * <pre>P(t) = C + u*a*cos(t) + w*b*sin(t)</pre>
 * with outward normal {@code N(t) = (b*cos(t), a*sin(t))} normalized, so each block is a slice
 * between two radial planes with 1m radial thickness ({@code a,b} are the CENTERLINE semi-axes;
 * the outer edge is 0.5m outside, the inner edge 0.5m inside), extruded 1m along the depth axis
 * {@code v = u x w}.
 *
 * <p>Uniform block width: equal ANGULAR steps on an ellipse give unequal arc lengths (blocks get
 * bigger on the flanks and smaller at the tips), so the ring is split into
 * {@code N = round(perimeter)} equal ~1m steps of ARC LENGTH (the perimeter and the per-wedge
 * angles come from a numeric integration of {@code ds/dt = sqrt(a^2 sin^2 t + b^2 cos^2 t)}).
 * Every wedge is therefore ~1m wide at the centerline, and consecutive wedges share their radial
 * faces exactly, so the loop tiles with no gaps.
 *
 * <p>All geometry here is deterministic world-space math (no baked models), so the same wedge
 * serves rendering, exact-mesh collision and raycasting on both the client and a dedicated
 * server.
 */
public final class EllipseGeometry {

    /** Shared parameters of one ellipse (identical for every voussoir). */
    public record EllipseResult(Vec3 center, Vec3 u, Vec3 w, Vec3 v,
                                double a, double b, double perimeter, int count,
                                double[] thetas) {
    }

    /** One wedge of the ring plus the cell that keys it (consecutive voussoirs that land in the
     *  same cell are merged into one segment by the server before placement). */
    public record Segment(EllipseBlockData data, BlockPos cell) {
    }

    /** Samples for the numeric arc-length integration of the perimeter. */
    private static final int ARC_SAMPLES = 8192;

    /** The minimum squared-curvature factor at the ellipse tips ({@code b^2/a}): below this the
     *  inner edge of the 1m-thick ring (curvature radius &lt; 0.6m) would pinch into a cusp and
     *  adjacent voussoirs would cross. */
    public static final double MIN_CURVATURE = 0.6;

    private EllipseGeometry() {
    }

    // ------------------------------------------------------------------
    // Ellipse construction (arc-length parameterization)
    // ------------------------------------------------------------------

    /**
     * Builds the ellipse: numeric perimeter, {@code N = round(perimeter)} equal ~1m arc-length
     * steps, and the angle of every step boundary. {@code thetas[i]} for i = 0..N with
     * {@code thetas[N] = 2*pi} (exact closure).
     */
    public static EllipseResult buildEllipse(Vec3 center, Vec3 u, Vec3 w,
                                             double a, double b, int layers) {
        double dTheta = Math.PI * 2.0 / ARC_SAMPLES;
        double[] s = new double[ARC_SAMPLES + 1];
        s[0] = 0.0;
        for (int i = 1; i <= ARC_SAMPLES; i++) {
            double t0 = (i - 1) * dTheta;
            double t1 = i * dTheta;
            double v0 = Math.sqrt(a * a * sin2(t0) + b * b * cos2(t0));
            double v1 = Math.sqrt(a * a * sin2(t1) + b * b * cos2(t1));
            s[i] = s[i - 1] + (v0 + v1) / 2.0 * dTheta;
        }
        double perimeter = s[ARC_SAMPLES];
        int count = Math.max(6, (int) Math.round(perimeter));
        double step = perimeter / count;
        double[] thetas = new double[count + 1];
        for (int i = 0; i < count; i++) {
            thetas[i] = inverseArcLength(s, dTheta, i * step);
        }
        thetas[count] = Math.PI * 2.0;
        return new EllipseResult(center, u, w, u.cross(w), a, b, perimeter, count, thetas);
    }

    /** The angle whose cumulative arc length equals {@code target} (monotone lookup). */
    private static double inverseArcLength(double[] s, double dTheta, double target) {
        int lo = 0;
        int hi = s.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s[mid] <= target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = s[hi] - s[lo];
        double f = span <= 1.0E-12 ? 0.0 : (target - s[lo]) / span;
        f = Math.max(0.0, Math.min(1.0, f));
        return (lo + f) * dTheta;
    }

    /** The wedge data of the {@code i}-th voussoir out of the ring, at depth layer {@code layer}
     *  (0 = the region's first depth cell). */
    public static EllipseBlockData blockData(EllipseResult r, int i, int layer) {
        double t0 = r.thetas()[i];
        double t1 = r.thetas()[i + 1];
        return new EllipseBlockData(
                r.center().x + r.v().x * layer, r.center().y + r.v().y * layer, r.center().z + r.v().z * layer,
                r.u().x, r.u().y, r.u().z,
                r.w().x, r.w().y, r.w().z,
                r.a(), r.b(), t0, t1 - t0);
    }

    /** A copy of {@code d} extended by {@code extraDeltaTheta} (merges consecutive voussoirs that
     *  land in the same cell, keeping the geometry tiled). */
    public static EllipseBlockData extend(EllipseBlockData d, double extraDeltaTheta) {
        return new EllipseBlockData(d.cx(), d.cy(), d.cz(),
                d.ux(), d.uy(), d.uz(),
                d.wx(), d.wy(), d.wz(),
                d.a(), d.b(), d.thetaStart(), d.deltaTheta() + extraDeltaTheta);
    }

    /** The ellipse the server will commit for a placed region when the player clicks a given cell
     *  face, plus the number of depth layers: the face's plane holds the ring (u = right,
     *  w = up), the face normal is the depth axis (v), and the region's projected cell-center
     *  extents give the semi-axes a/b (centerline) and the ring's depth layers. Shared by
     *  {@code BuilderServerHandler.ellipseBlocks} and the client's ghost preview so the curve
     *  shown while aiming is exactly what the click generates. Returns null when no ring can
     *  form (no face, too small, or too flat to close). */
    public record RegionEllipse(EllipseResult ellipse, int layers) {
    }

    /** Derives the ring for the region + clicked face (see {@link RegionEllipse}). */
    @Nullable
    public static RegionEllipse regionEllipse(BlockPos min, BlockPos max, BlockPos click, Direction face) {
        if (click == null || face == null) {
            return null;
        }
        FaceFrame frame = FaceFrame.of(click, face);
        Vec3 u = frame.right();
        Vec3 w = frame.up();
        double extentU = ArchGeometry.cellExtent(min, max, u);
        double extentW = ArchGeometry.cellExtent(min, max, w);
        if (extentU < 1.0 || extentW < 1.0) {
            return null;
        }
        double a = extentU / 2.0;
        double b = extentW / 2.0;
        if (a - 0.5 < 0.5 || b - 0.5 < 0.5) {
            return null;
        }
        if (b * b / a < MIN_CURVATURE) {
            return null;
        }
        double extentV = ArchGeometry.cellExtent(min, max, frame.forward());
        int layers = (int) Math.floor(extentV + 1.0E-6) + 1;
        Vec3 center = ArchGeometry.boxCenter(min, max);
        return new RegionEllipse(buildEllipse(center, u, w, a, b, layers), layers);
    }

    // ------------------------------------------------------------------
    // Wedge geometry
    // ------------------------------------------------------------------

    /** World-space centerline point at angle {@code t}. */
    public static Vec3 centerline(EllipseBlockData d, double t) {
        return new Vec3(d.cx(), d.cy(), d.cz())
                .add(new Vec3(d.ux(), d.uy(), d.uz()).scale(d.a() * Math.cos(t)))
                .add(new Vec3(d.wx(), d.wy(), d.wz()).scale(d.b() * Math.sin(t)));
    }

    /** Outward unit normal at angle {@code t}: {@code N = (b cos t, a sin t)} normalized. */
    private static Vec3 normalAt(EllipseBlockData d, double t) {
        double nx = d.b() * Math.cos(t);
        double ny = d.a() * Math.sin(t);
        double len = Math.sqrt(nx * nx + ny * ny);
        Vec3 u = new Vec3(d.ux(), d.uy(), d.uz());
        Vec3 w = new Vec3(d.wx(), d.wy(), d.wz());
        return len < 1.0E-9 ? w : u.scale(nx / len).add(w.scale(ny / len));
    }

    /** The world-space center of the wedge's centerline (its visual/collision pivot). */
    public static Vec3 wedgeCenter(EllipseBlockData d) {
        return centerline(d, d.thetaStart() + d.deltaTheta() / 2.0);
    }

    /**
     * The 8 world-space corners of the voussoir, in the same order as the arch wedges
     * A (inner,start,-depth), B (outer,start,-depth), C (outer,end,-depth), D (inner,end,-depth),
     * E (inner,start,+depth), F (outer,start,+depth), G (outer,end,+depth), H (inner,end,+depth).
     */
    public static Vec3[] wedgeVertices(EllipseBlockData d) {
        double t0 = d.thetaStart();
        double t1 = t0 + d.deltaTheta();
        double h = EllipseBlockData.DEPTH_HALF;
        Vec3 v = new Vec3(d.ux(), d.uy(), d.uz()).cross(new Vec3(d.wx(), d.wy(), d.wz()));
        Vec3 p0 = centerline(d, t0);
        Vec3 p1 = centerline(d, t1);
        Vec3 n0 = normalAt(d, t0);
        Vec3 n1 = normalAt(d, t1);
        Vec3 i0 = p0.subtract(n0.scale(h));
        Vec3 o0 = p0.add(n0.scale(h));
        Vec3 i1 = p1.subtract(n1.scale(h));
        Vec3 o1 = p1.add(n1.scale(h));
        return new Vec3[]{
                i0.subtract(v.scale(h)), o0.subtract(v.scale(h)), o1.subtract(v.scale(h)), i1.subtract(v.scale(h)),
                i0.add(v.scale(h)), o0.add(v.scale(h)), o1.add(v.scale(h)), i1.add(v.scale(h))
        };
    }

    /**
     * The 12 triangles of the closed wedge surface (6 faces x 2), in world space. Used verbatim
     * for the exact-mesh collision and raycast paths.
     */
    public static List<MeshCollisionShape.Tri> wedgeTriangles(EllipseBlockData d) {
        Vec3[] v = wedgeVertices(d);
        List<MeshCollisionShape.Tri> tris = new ArrayList<>(12);
        for (int[] face : ArchGeometry.wedgeFaces()) {
            Vec3 p0 = v[face[0]];
            Vec3 p1 = v[face[1]];
            Vec3 p2 = v[face[2]];
            Vec3 p3 = v[face[3]];
            tris.add(new MeshCollisionShape.Tri(
                    p0.x, p0.y, p0.z, p1.x, p1.y, p1.z, p2.x, p2.y, p2.z));
            tris.add(new MeshCollisionShape.Tri(
                    p0.x, p0.y, p0.z, p2.x, p2.y, p2.z, p3.x, p3.y, p3.z));
        }
        return tris;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Arc length of the ellipse with semi-axes {@code A}/{@code B} over {@code [t0, t1]}. */
    private static double arcLength(double A, double B, double t0, double t1) {
        int steps = 16;
        double h = (t1 - t0) / steps;
        double sum = 0.0;
        for (int i = 0; i < steps; i++) {
            double ta = t0 + i * h;
            double tb = ta + h;
            double va = Math.sqrt(A * A * sin2(ta) + B * B * cos2(ta));
            double vb = Math.sqrt(A * A * sin2(tb) + B * B * cos2(tb));
            sum += (va + vb) / 2.0 * h;
        }
        return sum;
    }

    /**
     * The six textured quads of the wedge for the renderer (world-space positions, same layout as
     * the arch renderer). The shared end-radial face is nudged a hair inward (along -tangent) so
     * it cannot z-fight with the next voussoir's coincident start face. Texture mapping
     * approximates the block texture per face (top on the outer arc, bottom on the inner arc,
     * side textures on the joints and ends), stretched over the face.
     */
    public static List<BakedQuad> wedgeQuads(EllipseBlockData d, BlockState state) {
        Vec3[] v = wedgeVertices(d);
        double t1 = d.thetaStart() + d.deltaTheta();
        Vec3 inward = tangent(d, t1).scale(-1.0); // the end face's outward is +tangent
        double nudge = 0.0005;
        for (int i : new int[]{2, 3, 6, 7}) { // C, D, G, H: the whole end-radial edge
            v[i] = v[i].add(inward.scale(nudge));
        }

        double innerArc = arcLength(d.a() - 0.5, d.b() - 0.5, d.thetaStart(), t1);
        double outerArc = arcLength(d.a() + 0.5, d.b() + 0.5, d.thetaStart(), t1);
        double midArc = arcLength(d.a(), d.b(), d.thetaStart(), t1);
        double[][] uvs = {
                {0.0, 0.0, innerArc, 0.0, innerArc, 1.0, 0.0, 1.0},      // inner
                {0.0, 0.0, 0.0, 1.0, outerArc, 1.0, outerArc, 0.0},      // outer
                {0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0},                // start radial
                {0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0},                // end radial
                {0.0, 0.0, 0.0, 1.0, midArc, 1.0, midArc, 0.0},          // back depth
                {0.0, 0.0, midArc, 0.0, midArc, 1.0, 0.0, 1.0}           // front depth
        };
        Direction[] dirs = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.NORTH, Direction.NORTH, Direction.NORTH};

        List<BakedQuad> quads = new ArrayList<>(6);
        int[][] faces = ArchGeometry.wedgeFaces();
        for (int f = 0; f < 6; f++) {
            int[] face = faces[f];
            Vec3 p0 = v[face[0]];
            Vec3 p1 = v[face[1]];
            Vec3 p2 = v[face[2]];
            Vec3 p3 = v[face[3]];
            BakedQuad.MaterialInfo info = ArchGeometry.materialFor(state, dirs[f]);
            if (info == null || info.sprite() == null) {
                continue;
            }
            Direction cull = ArchGeometry.nearestDirection(outwardNormal(p0, p1, p2));
            quads.add(new BakedQuad(
                    ArchGeometry.vec(p0), ArchGeometry.vec(p1), ArchGeometry.vec(p2), ArchGeometry.vec(p3),
                    ArchGeometry.uv(info.sprite(), (float) uvs[f][0], (float) uvs[f][1]),
                    ArchGeometry.uv(info.sprite(), (float) uvs[f][2], (float) uvs[f][3]),
                    ArchGeometry.uv(info.sprite(), (float) uvs[f][4], (float) uvs[f][5]),
                    ArchGeometry.uv(info.sprite(), (float) uvs[f][6], (float) uvs[f][7]),
                    cull, info));
        }
        return quads;
    }

    /** Tangent unit vector at angle {@code t} (increasing t direction). */
    private static Vec3 tangent(EllipseBlockData d, double t) {
        Vec3 u = new Vec3(d.ux(), d.uy(), d.uz());
        Vec3 w = new Vec3(d.wx(), d.wy(), d.wz());
        Vec3 tan = u.scale(-d.a() * Math.sin(t)).add(w.scale(d.b() * Math.cos(t)));
        double len = tan.length();
        return len < 1.0E-9 ? w : tan.scale(1.0 / len);
    }

    /** Computes the outward unit normal of the quad (p0,p1,p2) by its winding. */
    private static Vec3 outwardNormal(Vec3 p0, Vec3 p1, Vec3 p2) {
        Vec3 e1 = p1.subtract(p0);
        Vec3 e2 = p2.subtract(p0);
        Vec3 n = e1.cross(e2);
        double len = n.length();
        return len < 1.0E-9 ? new Vec3(0, 1, 0) : n.scale(1.0 / len);
    }

    private static double sin2(double t) {
        double s = Math.sin(t);
        return s * s;
    }

    private static double cos2(double t) {
        double c = Math.cos(t);
        return c * c;
    }

}
