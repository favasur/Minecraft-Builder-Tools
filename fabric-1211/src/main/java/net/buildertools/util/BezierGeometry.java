package net.buildertools.util;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The math behind the Bezier arch (the ALT+A mechanic): a quadratic Bezier band from the wall's
 * root node {@code A} to the final click destination {@code B}, pulled toward the control handle
 * {@code C} (the extended wall itself, which disappears into the curve).
 *
 * <p>Point: {@code P(t) = (1-t)^2 A + 2(1-t)t C + t^2 B}. The curve leaves A parallel to the
 * handle (along the wall - no early bend), is pulled toward C (the wall's far end), and always
 * terminates EXACTLY at B, so the arch's length is free (never capped by the wall's span) and its
 * final voussoir's end face sits on the click destination. The curve is sampled into ~1m voussoirs
 * by arc length; each wedge is a curved slice of the band (0.5m inside + 0.5m outside the
 * centerline along the in-plane normal, extruded 1m along the depth axis {@code v}), tessellated
 * along the curve so it renders and collides as a smooth curve.
 *
 * <p>All geometry is deterministic world-space math (no baked models), so the same wedge serves
 * rendering, exact-mesh collision and raycasting on both the client and a dedicated server.
 */
public final class BezierGeometry {

    /** Shared parameters of one Bezier arch (identical for every voussoir). {@code v} is the
     *  depth axis (the plane normal) and {@code w} the rise axis (the in-plane normal at the
     *  curve's start), used to offset the band into the wall's depth columns and layers. */
    public record BezierArch(Vec3 a, Vec3 c, Vec3 b, Vec3 v, Vec3 w, int count, double[] ts) {
    }

    private BezierGeometry() {
    }

    /** World-space point on the curve at parameter {@code t}. */
    public static Vec3 point(Vec3 a, Vec3 c, Vec3 b, double t) {
        double mt = 1.0 - t;
        return a.scale(mt * mt).add(c.scale(2.0 * mt * t)).add(b.scale(t * t));
    }

    /** First derivative (tangent, unnormalized) at parameter {@code t}. */
    private static Vec3 derivative(Vec3 a, Vec3 c, Vec3 b, double t) {
        double mt = 1.0 - t;
        return a.scale(-2.0 * mt).add(c.scale(2.0 * (mt - t))).add(b.scale(2.0 * t));
    }

    /** Tangent unit vector at parameter {@code t}. */
    private static Vec3 tangent(Vec3 a, Vec3 c, Vec3 b, double t) {
        Vec3 d = derivative(a, c, b, t);
        double len = d.length();
        return len < 1.0E-9 ? b.subtract(a).normalize() : d.scale(1.0 / len);
    }

    /**
     * Builds the arch for nodes {@code a}, {@code b} and handle {@code c}: samples the curve
     * densely, accumulates its arc length and picks the parameter values at 1m boundaries, so
     * every voussoir is ~1m wide at the centerline and the last wedge ends exactly at {@code t=1}
     * (the click destination).
     */
    public static BezierArch build(Vec3 a, Vec3 c, Vec3 b, Vec3 v, Vec3 w) {
        int n = 2048;
        double[] cum = new double[n + 1];
        Vec3 prev = point(a, c, b, 0.0);
        for (int i = 1; i <= n; i++) {
            Vec3 cur = point(a, c, b, (double) i / n);
            cum[i] = cum[i - 1] + prev.distanceTo(cur);
            prev = cur;
        }
        double total = cum[n];
        int count = Math.max((int) Math.round(total), 2);
        double[] ts = new double[count + 1];
        ts[0] = 0.0;
        for (int m = 1; m < count; m++) {
            double target = total * m / count;
            int lo = 0, hi = n;
            while (lo < hi - 1) {
                int mid = (lo + hi) / 2;
                if (cum[mid] <= target) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double span = cum[hi] - cum[lo];
            double f = span <= 1.0E-12 ? 0.0 : (target - cum[lo]) / span;
            ts[m] = Math.max(0.0, Math.min(1.0, (lo + f) / n));
        }
        ts[count] = 1.0;
        return new BezierArch(a, c, b, v, w, count, ts);
    }

    /** The wedge data of the {@code i}-th voussoir, shifted by {@code offset} (depth column /
     *  radial layer of the wall). */
    public static BezierBlockData blockData(BezierArch arch, int i, Vec3 offset) {
        double t0 = arch.ts()[i];
        double t1 = arch.ts()[i + 1];
        Vec3 a = arch.a().add(offset);
        Vec3 c = arch.c().add(offset);
        Vec3 b = arch.b().add(offset);
        return new BezierBlockData(
                a.x, a.y, a.z,
                c.x, c.y, c.z,
                b.x, b.y, b.z,
                arch.v().x, arch.v().y, arch.v().z,
                t0, t1);
    }

    /** A copy of {@code d} whose end parameter is extended (merges consecutive voussoirs that
     *  land in the same cell, keeping the geometry tiled). */
    public static BezierBlockData extend(BezierBlockData d, double extraT) {
        return new BezierBlockData(
                d.ax(), d.ay(), d.az(),
                d.cx(), d.cy(), d.cz(),
                d.bx(), d.by(), d.bz(),
                d.vx(), d.vy(), d.vz(),
                d.t0(), d.t1() + extraT);
    }

    /** The world-space center of the wedge's centerline (its visual/collision pivot). */
    public static Vec3 wedgeCenter(BezierBlockData d) {
        Vec3 a = new Vec3(d.ax(), d.ay(), d.az());
        Vec3 c = new Vec3(d.cx(), d.cy(), d.cz());
        Vec3 b = new Vec3(d.bx(), d.by(), d.bz());
        return point(a, c, b, (d.t0() + d.t1()) / 2.0);
    }

    /** In-plane unit normal (perpendicular to the tangent, in the arch plane) at parameter t:
     *  {@code v x tangent}, so it consistently points to one side of the curve. */
    private static Vec3 normalAt(BezierBlockData d, double t) {
        Vec3 a = new Vec3(d.ax(), d.ay(), d.az());
        Vec3 c = new Vec3(d.cx(), d.cy(), d.cz());
        Vec3 b = new Vec3(d.bx(), d.by(), d.bz());
        Vec3 v = new Vec3(d.vx(), d.vy(), d.vz());
        Vec3 n = v.cross(tangent(a, c, b, t));
        double len = n.length();
        return len < 1.0E-9 ? v.cross(b.subtract(a).normalize()) : n.scale(1.0 / len);
    }

    /**
     * The 8 world-space corners of the voussoir, in the same order as the arch wedges
     * A (inner,start,-depth), B (outer,start,-depth), C (outer,end,-depth), D (inner,end,-depth),
     * E (inner,start,+depth), F (outer,start,+depth), G (outer,end,+depth), H (inner,end,+depth).
     */
    public static Vec3[] wedgeVertices(BezierBlockData d) {
        double h = BezierBlockData.DEPTH_HALF;
        Vec3 v = new Vec3(d.vx(), d.vy(), d.vz());
        Vec3 p0 = wedgeCenterOf(d, d.t0());
        Vec3 p1 = wedgeCenterOf(d, d.t1());
        Vec3 n0 = normalAt(d, d.t0());
        Vec3 n1 = normalAt(d, d.t1());
        Vec3 i0 = p0.subtract(n0.scale(h));
        Vec3 o0 = p0.add(n0.scale(h));
        Vec3 i1 = p1.subtract(n1.scale(h));
        Vec3 o1 = p1.add(n1.scale(h));
        return new Vec3[]{
                i0.subtract(v.scale(h)), o0.subtract(v.scale(h)), o1.subtract(v.scale(h)), i1.subtract(v.scale(h)),
                i0.add(v.scale(h)), o0.add(v.scale(h)), o1.add(v.scale(h)), i1.add(v.scale(h))
        };
    }

    /** World-space centerline point at parameter {@code t}. */
    private static Vec3 wedgeCenterOf(BezierBlockData d, double t) {
        return point(new Vec3(d.ax(), d.ay(), d.az()),
                new Vec3(d.cx(), d.cy(), d.cz()),
                new Vec3(d.bx(), d.by(), d.bz()), t);
    }

    /** Arc length of the curve over the parameter range {@code [t0, t1]}. */
    private static double arcLength(BezierBlockData d, double t0, double t1) {
        int steps = 16;
        double h = (t1 - t0) / steps;
        Vec3 a = new Vec3(d.ax(), d.ay(), d.az());
        Vec3 c = new Vec3(d.cx(), d.cy(), d.cz());
        Vec3 b = new Vec3(d.bx(), d.by(), d.bz());
        double sum = 0.0;
        Vec3 prev = point(a, c, b, t0);
        for (int i = 1; i <= steps; i++) {
            Vec3 cur = point(a, c, b, t0 + i * h);
            sum += prev.distanceTo(cur);
            prev = cur;
        }
        return sum;
    }

    /** The wedge surface as quads (same layout as {@link ArchGeometry#wedgeFaces(ArchBlockData)}),
     *  tessellated along the curve with every corner exactly on the Bezier and the 0.5m radial
     *  offset along the exact in-plane normal, so the band renders as a smooth curve. */
    public static List<ArchGeometry.WedgeFace> wedgeFaces(BezierBlockData d) {
        int n = ArchGeometry.ARC_SUBDIVISIONS;
        double t0 = d.t0();
        double t1 = d.t1();
        double h = BezierBlockData.DEPTH_HALF;
        Vec3 v = new Vec3(d.vx(), d.vy(), d.vz());
        Vec3[][] inner = new Vec3[n + 1][2];
        Vec3[][] outer = new Vec3[n + 1][2];
        double[] cumInner = new double[n + 1];
        double[] cumOuter = new double[n + 1];
        double[] cumMid = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = t0 + (t1 - t0) * i / n;
            Vec3 p = wedgeCenterOf(d, t);
            Vec3 nn = normalAt(d, t);
            Vec3 pi = p.subtract(nn.scale(h));
            Vec3 po = p.add(nn.scale(h));
            inner[i][0] = pi.subtract(v.scale(h));
            inner[i][1] = pi.add(v.scale(h));
            outer[i][0] = po.subtract(v.scale(h));
            outer[i][1] = po.add(v.scale(h));
            cumInner[i] = arcLength(d, 0.0, t);
            // The tile phase is measured from the ARCH START (t=0), not this wedge's own t0, so
            // the texture continues seamlessly across voussoir seams instead of restarting every
            // ~1m (the stretched-stripe look). putVertex wraps the phase into [0,1) per meter,
            // so the pattern tiles once per meter along the whole curve.
            cumOuter[i] = cumInner[i];
            cumMid[i] = cumInner[i];
        }
        // The end face is shared with the next voussoir's start face; nudge it inward so the
        // coincident faces cannot z-fight.
        Vec3 a = new Vec3(d.ax(), d.ay(), d.az());
        Vec3 c = new Vec3(d.cx(), d.cy(), d.cz());
        Vec3 b = new Vec3(d.bx(), d.by(), d.bz());
        Vec3 inward = tangent(a, c, b, t1).scale(-1.0);
        for (int i : new int[]{n}) {
            inner[i][0] = inner[i][0].add(inward.scale(0.0005));
            inner[i][1] = inner[i][1].add(inward.scale(0.0005));
            outer[i][0] = outer[i][0].add(inward.scale(0.0005));
            outer[i][1] = outer[i][1].add(inward.scale(0.0005));
        }

        List<ArchGeometry.WedgeFace> faces = new ArrayList<>(4 * n + 2);
        for (int i = 0; i < n; i++) {
            // inner arc face (toward the curve's inside)
            faces.add(new ArchGeometry.WedgeFace(new Vec3[]{inner[i][0], inner[i + 1][0], inner[i + 1][1], inner[i][1]},
                    new double[]{cumInner[i], cumInner[i + 1], cumInner[i + 1], cumInner[i]},
                    new double[]{0, 0, 1, 1}, ArchGeometry.FACE_INNER));
            // outer arc face (away from the curve's inside)
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
        // start face (flat): A, E, F, B
        faces.add(new ArchGeometry.WedgeFace(new Vec3[]{inner[0][0], inner[0][1], outer[0][1], outer[0][0]},
                new double[]{0, 0, 1, 1}, new double[]{0, 1, 1, 0}, ArchGeometry.FACE_START));
        // end face (flat, nudged): D, C, G, H
        faces.add(new ArchGeometry.WedgeFace(new Vec3[]{inner[n][0], outer[n][0], outer[n][1], inner[n][1]},
                new double[]{0, 1, 1, 0}, new double[]{0, 0, 1, 1}, ArchGeometry.FACE_END));
        return faces;
    }

    /** The triangles of the closed wedge surface (2 per face), in world space. Used verbatim for
     *  the exact-mesh collision and raycast paths. */
    public static List<MeshCollisionShape.Tri> wedgeTriangles(BezierBlockData d) {
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

    /** The textured quads of the wedge for the renderer (world-space positions, tessellated along
     *  the curve so the band renders as a smooth curve). */
    public static List<BakedQuad> wedgeQuads(BezierBlockData d, BlockState state) {
        Direction[] spriteDirs = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.NORTH,
                Direction.NORTH, Direction.NORTH};
        List<BakedQuad> quads = new ArrayList<>(4 * ArchGeometry.ARC_SUBDIVISIONS + 2);
        for (ArchGeometry.WedgeFace face : wedgeFaces(d)) {
            Vec3[] c = face.corners();
            TextureAtlasSprite sprite = ArchGeometry.spriteFor(state, spriteDirs[face.kind()]);
            if (sprite == null) {
                continue;
            }
            Direction cull = Direction.getNearest(
                    (float) outwardNormal(c[0], c[1], c[2]).x,
                    (float) outwardNormal(c[0], c[1], c[2]).y,
                    (float) outwardNormal(c[0], c[1], c[2]).z);
            int[] vertices = new int[32];
            ArchGeometry.putVertex(vertices, 0, c[0], (float) face.u()[0], (float) face.v()[0], sprite);
            ArchGeometry.putVertex(vertices, 1, c[1], (float) face.u()[1], (float) face.v()[1], sprite);
            ArchGeometry.putVertex(vertices, 2, c[2], (float) face.u()[2], (float) face.v()[2], sprite);
            ArchGeometry.putVertex(vertices, 3, c[3], (float) face.u()[3], (float) face.v()[3], sprite);
            quads.add(new BakedQuad(vertices, -1, cull, sprite, true));
        }
        return quads;
    }

    /** Computes the outward unit normal of the quad (p0,p1,p2) by its winding. */
    private static Vec3 outwardNormal(Vec3 p0, Vec3 p1, Vec3 p2) {
        Vec3 e1 = p1.subtract(p0);
        Vec3 e2 = p2.subtract(p0);
        Vec3 n = e1.cross(e2);
        double len = n.length();
        return len < 1.0E-9 ? new Vec3(0, 1, 0) : n.scale(1.0 / len);
    }
}
