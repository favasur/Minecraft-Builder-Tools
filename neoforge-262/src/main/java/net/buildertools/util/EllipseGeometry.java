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
     * The triangles of the closed wedge surface (2 per face), in world space. Used verbatim for
     * the exact-mesh collision and raycast paths - the arc faces are tessellated, so the ring
     * collides (and is hit by placement/raycasts) as a smooth curve.
     */
    public static List<MeshCollisionShape.Tri> wedgeTriangles(EllipseBlockData d) {
        List<MeshCollisionShape.Tri> tris = new ArrayList<>(4 * ArchGeometry.ARC_SUBDIVISIONS + 4);
        for (ArchGeometry.WedgeFace face : wedgeFaces(d)) {
            Vec3[] c = face.corners();
            tris.add(new MeshCollisionShape.Tri(
                    c[0].x, c[0].y, c[0].z, c[1].x, c[1].y, c[1].z, c[2].x, c[2].y, c[2].z));
            tris.add(new MeshCollisionShape.Tri(
                    c[0].x, c[0].y, c[0].z, c[2].x, c[2].y, c[2].z, c[3].x, c[3].y, c[3].z));
        }
        return tris;
    }

    /** The wedge surface as quads (same layout as {@link ArchGeometry#wedgeFaces(ArchBlockData)}),
     *  tessellated along the ellipse parameter with every corner exactly on the ellipse and the
     *  0.5m radial offset along the exact outward normal, so the ring renders as a smooth curve. */
    public static List<ArchGeometry.WedgeFace> wedgeFaces(EllipseBlockData d) {
        int n = ArchGeometry.ARC_SUBDIVISIONS;
        double t0 = d.thetaStart();
        double t1 = t0 + d.deltaTheta();
        double h = EllipseBlockData.DEPTH_HALF;
        Vec3 v = new Vec3(d.ux(), d.uy(), d.uz()).cross(new Vec3(d.wx(), d.wy(), d.wz()));
        Vec3[][] inner = new Vec3[n + 1][2];
        Vec3[][] outer = new Vec3[n + 1][2];
        double[] cumInner = new double[n + 1];
        double[] cumOuter = new double[n + 1];
        double[] cumMid = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = t0 + (t1 - t0) * i / n;
            Vec3 p = centerline(d, t);
            Vec3 nn = normalAt(d, t);
            Vec3 pi = p.subtract(nn.scale(h));
            Vec3 po = p.add(nn.scale(h));
            inner[i][0] = pi.subtract(v.scale(h));
            inner[i][1] = pi.add(v.scale(h));
            outer[i][0] = po.subtract(v.scale(h));
            outer[i][1] = po.add(v.scale(h));
            // The tile phase is measured from the ellipse's theta=0 (its own axes), not this
            // wedge's own thetaStart, so the texture continues seamlessly across voussoir seams
            // and around the whole loop instead of restarting every ~1m (the stretched-stripe
            // look). putVertex wraps the phase into [0,1) per meter, so the pattern tiles once
            // per meter along the perimeter.
            cumInner[i] = arcLength(d.a(), d.b(), 0.0, t);
            cumOuter[i] = cumInner[i];
            cumMid[i] = cumInner[i];
        }
        // The end-radial edge is shared with the next voussoir's start edge; nudge it inward
        // (the ellipse's end face outward is +tangent, so inward is -tangent) so the coincident
        // faces cannot z-fight.
        Vec3 inward = tangent(d, t1).scale(-1.0);
        for (int i : new int[]{n}) {
            inner[i][0] = inner[i][0].add(inward.scale(0.0005));
            inner[i][1] = inner[i][1].add(inward.scale(0.0005));
            outer[i][0] = outer[i][0].add(inward.scale(0.0005));
            outer[i][1] = outer[i][1].add(inward.scale(0.0005));
        }

        List<ArchGeometry.WedgeFace> faces = new ArrayList<>(4 * n + 2);
        for (int i = 0; i < n; i++) {
            // inner arc face (toward the ring's inside)
            faces.add(new ArchGeometry.WedgeFace(new Vec3[]{inner[i][0], inner[i + 1][0], inner[i + 1][1], inner[i][1]},
                    new double[]{cumInner[i], cumInner[i + 1], cumInner[i + 1], cumInner[i]},
                    new double[]{0, 0, 1, 1}, ArchGeometry.FACE_INNER));
            // outer arc face (away from the ring's inside)
            faces.add(new ArchGeometry.WedgeFace(new Vec3[]{outer[i][0], outer[i][1], outer[i + 1][1], outer[i + 1][0]},
                    new double[]{cumOuter[i], cumOuter[i], cumOuter[i + 1], cumOuter[i + 1]},
                    new double[]{0, 1, 1, 0}, ArchGeometry.FACE_OUTER));
            // back depth face (-v)
            faces.add(new ArchGeometry.WedgeFace(new Vec3[]{inner[i][0], outer[i][0], outer[i + 1][0], inner[i + 1][0]},
                    new double[]{cumMid[i], cumMid[i], cumMid[i + 1], cumMid[i + 1]},
                    new double[]{0, 1, 1, 0}, ArchGeometry.FACE_BACK));
            // front depth face (+v)
            faces.add(new ArchGeometry.WedgeFace(new Vec3[]{inner[i][1], inner[i + 1][1], outer[i + 1][1], outer[i][1]},
                    new double[]{cumMid[i], cumMid[i + 1], cumMid[i + 1], cumMid[i]},
                    new double[]{0, 0, 1, 1}, ArchGeometry.FACE_FRONT));
        }
        // start radial face (flat): A, E, F, B
        faces.add(new ArchGeometry.WedgeFace(new Vec3[]{inner[0][0], inner[0][1], outer[0][1], outer[0][0]},
                new double[]{0, 0, 1, 1}, new double[]{0, 1, 1, 0}, ArchGeometry.FACE_START));
        // end radial face (flat, nudged): D, C, G, H
        faces.add(new ArchGeometry.WedgeFace(new Vec3[]{inner[n][0], outer[n][0], outer[n][1], inner[n][1]},
                new double[]{0, 1, 1, 0}, new double[]{0, 0, 1, 1}, ArchGeometry.FACE_END));
        return faces;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Arc length of the ellipse with semi-axes {@code A}/{@code B} over [t0, t1]. */
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
     * The textured quads of the wedge for the renderer (world-space positions, tessellated along
     * the ellipse so the ring renders as a smooth curve). Texture mapping approximates the block
     * texture per face (top on the outer arc, bottom on the inner arc, side textures on the
     * joints and ends), tiling once per meter (the {@link ArchGeometry#uv} helper wraps the tile
     * coordinates so the texture never samples past the sprite's atlas rectangle).
     */
    public static List<BakedQuad> wedgeQuads(EllipseBlockData d, BlockState state) {
        Direction[] spriteDirs = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.NORTH,
                Direction.NORTH, Direction.NORTH};
        List<BakedQuad> quads = new ArrayList<>(4 * ArchGeometry.ARC_SUBDIVISIONS + 2);
        for (ArchGeometry.WedgeFace face : wedgeFaces(d)) {
            Vec3[] c = face.corners();
            BakedQuad.MaterialInfo info = ArchGeometry.materialFor(state, spriteDirs[face.kind()]);
            if (info == null || info.sprite() == null) {
                continue;
            }
            Direction cull = ArchGeometry.nearestDirection(outwardNormal(c[0], c[1], c[2]));
            ArchGeometry.addFaceQuads(quads, face, info, cull);
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
