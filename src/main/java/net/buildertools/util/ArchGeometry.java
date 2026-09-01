package net.buildertools.util;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The math behind the Arching building mechanic: a straight row of 1m cubes is re-shaped into a
 * circular arch of tapered voussoirs (wedges wider at the top than at the bottom, so the arch has
 * no gaps).
 *
 * <p>Given the row's two end points (the Span {@code S}) and the clicked block (the Rise
 * {@code H} - the perpendicular distance from the chord to the click), the arch's centerline
 * radius is
 * <pre>R = H/2 + S^2 / (8H)</pre>
 * the arch center sits on the perpendicular bisector of the chord at distance {@code R - H} from
 * its midpoint (on the side opposite the rise), and the arc between the end points spans
 * {@code pi - 2*atan2(R-H, S/2)} radians through the apex. Each of the {@code N} row blocks
 * becomes one wedge spanning an equal angular slice of that arc ({@code deltaTheta = total/N} -
 * for a shallow arch this is within a few percent of the spec's 1/R, and closing the arc exactly
 * is what guarantees the voussoirs tile with no gaps), with radial thickness 1m
 * ({@code R_out = R + 0.5}, {@code R_in = R - 0.5}) extruded 1m along the arch's depth axis.
 *
 * <p>All geometry here is deterministic world-space math (no baked models), so the same wedge
 * serves rendering, exact-mesh collision and raycasting on both the client and a dedicated
 * server.
 */
public final class ArchGeometry {

    /** Shared parameters of one arch (identical for every voussoir). */
    public record ArchResult(Vec3 origin, Vec3 u, Vec3 w, double radius,
                             double thetaStart, double totalAngle) {
    }

    /**
     * The arch the server will commit for a stretched wall region when the player clicks a given
     * cell face, plus the parameters the commit needs (voussoir count and the box centre).
     * Exactly one of {@code arch} (the classic circular bow for thin rows) and {@code bezier}
     * (the Bezier wall arch, see {@link #regionArch}) is non-null. Shared by
     * {@code BuilderServerHandler.archBlocks} and the client's ghost preview so the curve shown
     * while aiming is exactly what the click produces.
     */
    public record RegionArch(ArchResult arch, BezierGeometry.BezierArch bezier, int count, double span,
                             Vec3 center) {
    }

    /** Minimum rise (m) below which the click is treated as being on the chord itself. */
    public static final double MIN_RISE = 0.5;

    private ArchGeometry() {
    }

    /**
     * Computes the arch through {@code start} and {@code end} (centers of the first and last
     * span cells, Span {@code S}) in the plane spanned by the unit vectors {@code u} and
     * {@code w}, bulging toward the +w side by the Rise {@code H}. The Rise is capped at
     * {@code S/2} so the arc can never exceed a semicircle - a click farther than that still
     * arches toward it (a full semicircle), but the arc can never wrap past the chord and
     * produce an inverted (opposite-facing) arch. Returns null when the input cannot form a
     * valid arch (span shorter than 1m, or a click on the chord itself).
     */
    @Nullable
    public static ArchResult computeArchInPlane(Vec3 start, Vec3 end, Vec3 u, Vec3 w, Vec3 click) {
        double span = start.distanceTo(end);
        if (span < 1.0) {
            return null;
        }
        Vec3 mid = start.add(end).scale(0.5);
        double rise = click.subtract(mid).dot(w);
        if (Math.abs(rise) < MIN_RISE) {
            return null;
        }
        if (rise < 0) {
            w = w.scale(-1.0);
            rise = -rise;
        }
        // Never more than a semicircle: the arc always bulges toward the click and can never
        // wrap around the chord's far side (the inverted-C failure).
        if (rise > span / 2.0) {
            rise = span / 2.0;
        }
        double radius = rise / 2.0 + span * span / (8.0 * rise);
        // Angles: the arc runs from start (pi - phiB) through the apex (pi/2) to end (phiB).
        double phiB = Math.atan2(radius - rise, span / 2.0);
        double thetaStart = Math.PI - phiB;
        double total = thetaStart - phiB;
        return new ArchResult(mid.subtract(w.scale(radius - rise)), u, w, radius,
                thetaStart, total);
    }

    /**
     * The wedge data of the fan fallback (see {@link #regionArch}): the i-th cell along the
     * span (counting from the pivot/far edge) becomes a voussoir at radius {@code 0.5 + i}
     * around the pivot, so the wall bows toward the click as a solid curved band - every cell
     * of the wall stays occupied. The bend angle eases in quadratically (the first cells stay
     * nearly straight, the bend grows toward the click end) so the curve does not start
     * bending at the very first block of the wall.
     */
    public static ArchBlockData fanBlockData(ArchResult arch, int i, int count) {
        // Quadratic easing: the cell's angular span is the difference of the squared progress
        // so consecutive wedges tile the arc with no gaps and the first cells barely rotate.
        double t0 = arch.totalAngle() * (double) (i * i) / (double) (count * count);
        double t1 = arch.totalAngle() * (double) ((i + 1) * (i + 1)) / (double) (count * count);
        // 1m cells along the span: the i-th cell's centerline sits i meters from the pivot.
        double radius = 0.5 + i;
        return new ArchBlockData(
                arch.origin().x, arch.origin().y, arch.origin().z,
                arch.u().x, arch.u().y, arch.u().z,
                arch.w().x, arch.w().y, arch.w().z,
                t0, t1 - t0, radius);
    }

    /**
     * Derives the arch that a click on the given cell face would commit for the stretched wall
     * region {@code [min, max]}. The span is the region's LARGEST extent (whatever axis it lies
     * along) so a wall bowed toward the click keeps its whole body as the band - clicking the
     * side of an 8m-long wall arches the full 8m, its tall-but-narrow cousin arches along its
     * height - and the rise follows the click's HORIZONTAL perpendicular offset from the chord
     * (its height never sags or flips the arch). The rise is capped at {@code span/2} so the
     * arch always bulges toward the click and can never wrap into an inverted C. When the click
     * sits beyond the wall's end (no perpendicular offset but a real offset along the span) the
     * wall bows around its far edge instead (the {@code fan} fallback), keeping every cell of
     * the wall as a voussoir. Returns null when no arch can form (no face, a region with no
     * real span, or a click on the chord itself). This is the single source of truth for both
     * the server commit and the client ghost preview.
     */
    @Nullable
    public static RegionArch regionArch(BlockPos min, BlockPos max, BlockPos click, Direction face) {
        if (click == null || face == null) {
            return null;
        }
        long ex = max.getX() - min.getX();
        long ey = max.getY() - min.getY();
        long ez = max.getZ() - min.getZ();
        double span = Math.max(Math.max(ex, ey), ez);
        if (span < 1.0) {
            return null;
        }
        Vec3 u = ex >= ey && ex >= ez ? new Vec3(1, 0, 0)
                : ey >= ez ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
        Vec3 center = boxCenter(min, max);
        Vec3 clickPoint = FaceFrame.of(click, face).origin();
        Vec3 horiz = new Vec3(clickPoint.x - center.x, 0, clickPoint.z - center.z);
        // Point the span toward the click's projected position (the arch leans its way).
        if (horiz.dot(u) < 0) {
            u = u.scale(-1.0);
        }
        // The rise: the HORIZONTAL perpendicular of the click from the chord (its height never
        // sags the arch - a ground-level click to the side still bends the wall at its own
        // height). Thin ribbons arch with the classic bridge bow (rise capped so the band hugs
        // the strip and the arc can never wrap into an inverted C); walls bow around their far
        // edge - every cell of the wall becomes a voussoir in place, so the wall bends toward
        // the click as a solid curved band instead of dissolving (the fan).
        Vec3 perp = horiz.subtract(u.scale(horiz.dot(u)));
        double rise = perp.length();
        double along = horiz.dot(u);
        Vec3 w;
        if (rise >= 1.0E-4) {
            w = perp.scale(1.0 / rise);
        } else if (ey >= ex && ey >= ez) {
            double hx = Math.abs(horiz.x) > 1.0E-4 ? horiz.x : 1.0;
            double len = Math.sqrt(hx * hx + horiz.z * horiz.z);
            w = new Vec3(hx / len, 0, horiz.z / len);
        } else if (u.x != 0) {
            w = new Vec3(0, 0, 1);
        } else {
            w = new Vec3(1, 0, 0);
        }
        boolean ribbon = u.x != 0 ? ey <= 1.5 && ez <= 1.5
                : u.y != 0 ? ex <= 1.5 && ez <= 1.5 : ex <= 1.5 && ey <= 1.5;
        if (ribbon && rise >= MIN_RISE) {
            Vec3 start = center.subtract(u.scale(span / 2.0));
            Vec3 end = center.add(u.scale(span / 2.0));
            // The rise is also capped so the arch hugs the strip it arches (a click far to the
            // side still bends it, just not meters away): the band's inner edge stays within
            // the strip's own thickness.
            double depth = cellExtent(min, max, w);
            double riseEff = Math.min(rise, depth / 2.0 + 1.5);
            if (riseEff < MIN_RISE) {
                return null;
            }
            ArchResult arch = computeArchInPlane(start, end, u, w,
                    center.add(w.scale(riseEff * 1.0001)));
            if (arch == null) {
                return null;
            }
            // Spec: every voussoir is ~1m wide at the centerline (Δθ = 1/R), so the arch is
            // split into round(arcLength) voussoirs - a deep arch (arc length >> span) must
            // get more, narrower wedges, or each piece would be ~2m long and look chunky.
            int count = Math.max((int) Math.round(span) + 1,
                    (int) Math.round(arch.totalAngle() * arch.radius()));
            return new RegionArch(arch, null, count, span, center);
        }
        // The Bezier wall arch (a wall, or any region whose click sits beyond its end): node A
        // is the wall's root (beginning of the wall), node B is the final click destination and
        // the control handle C is the extended wall itself - the curve leaves A parallel to the
        // handle (along the wall - no early bend), is pulled toward C, and always terminates
        // EXACTLY at B, so the arch's length is free (never capped by the wall's span) and its
        // final voussoir's end face sits on the click destination. The wall's cells become the
        // curve's voussoirs (the wall disappears into the curve).
        double dist = Math.sqrt(rise * rise + along * along);
        if (dist < 1.0) {
            return null;
        }
        Vec3 a = center.subtract(u.scale(span / 2.0));
        Vec3 c = center.add(u.scale(span / 2.0));
        Vec3 b = center.add(horiz);
        if (b.distanceToSqr(a) < 1.0) {
            return null;
        }
        BezierGeometry.BezierArch bezier = BezierGeometry.build(a, c, b, u.cross(w), w);
        return new RegionArch(null, bezier, bezier.count(), span, center);
    }

    /** The centre of the region box (the mean of its cell centres). */
    public static Vec3 boxCenter(BlockPos min, BlockPos max) {
        return new Vec3(min.getX() + (max.getX() - min.getX() + 1) / 2.0,
                min.getY() + (max.getY() - min.getY() + 1) / 2.0,
                min.getZ() + (max.getZ() - min.getZ() + 1) / 2.0);
    }

    /** The span (max - min) of the region's cell centres projected onto the unit vector. */
    public static double cellExtent(BlockPos min, BlockPos max, Vec3 axis) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    double px = min.getX() + 0.5 + dx * (max.getX() - min.getX());
                    double py = min.getY() + 0.5 + dy * (max.getY() - min.getY());
                    double pz = min.getZ() + 0.5 + dz * (max.getZ() - min.getZ());
                    double p = px * axis.x + py * axis.y + pz * axis.z;
                    lo = Math.min(lo, p);
                    hi = Math.max(hi, p);
                }
            }
        }
        return hi - lo;
    }

    /**
     * The wedge data of the {@code i}-th voussoir out of {@code count} (0 = the start end of the
     * row). The arc angle is split evenly so the wedges tile the whole arc with no gaps: wedge
     * {@code i} spans {@code [end - perBlock, end]} where {@code end = thetaStart - i*perBlock},
     * so the first wedge's end sits exactly on the row's start point and the last wedge's start
     * exactly on its end point.
     */
    public static ArchBlockData blockData(ArchResult arch, int i, int count) {
        double perBlock = arch.totalAngle() / count;
        double end = arch.thetaStart() - i * perBlock;
        return new ArchBlockData(
                arch.origin().x, arch.origin().y, arch.origin().z,
                arch.u().x, arch.u().y, arch.u().z,
                arch.w().x, arch.w().y, arch.w().z,
                end - perBlock, perBlock, arch.radius());
    }

    /** The depth unit vector of the arch (perpendicular to the span/rise plane). */
    public static Vec3 depth(ArchBlockData a) {
        return new Vec3(a.ux(), a.uy(), a.uz()).cross(new Vec3(a.wx(), a.wy(), a.wz()));
    }

    /** World-space point on the arch at angle {@code theta} and radius {@code r}. */
    private static Vec3 polar(ArchBlockData a, double theta, double r) {
        return new Vec3(a.ox(), a.oy(), a.oz())
                .add(new Vec3(a.ux(), a.uy(), a.uz()).scale(r * Math.cos(theta)))
                .add(new Vec3(a.wx(), a.wy(), a.wz()).scale(r * Math.sin(theta)));
    }

    /** Tangent unit vector at angle {@code theta} (increasing theta direction). */
    private static Vec3 tangent(ArchBlockData a, double theta) {
        return new Vec3(a.ux(), a.uy(), a.uz()).scale(-Math.sin(theta))
                .add(new Vec3(a.wx(), a.wy(), a.wz()).scale(Math.cos(theta)));
    }

    /** The world-space center of the wedge's centerline (its visual/collision pivot). */
    public static Vec3 wedgeCenter(ArchBlockData a) {
        return polar(a, a.thetaStart() + a.deltaTheta() / 2.0, a.radius());
    }

    /**
     * The 8 world-space corners of the voussoir, in the order
     * A (inner,start,-depth), B (outer,start,-depth), C (outer,end,-depth), D (inner,end,-depth),
     * E (inner,start,+depth), F (outer,start,+depth), G (outer,end,+depth), H (inner,end,+depth).
     */
    public static Vec3[] wedgeVertices(ArchBlockData a) {
        double t0 = a.thetaStart();
        double t1 = t0 + a.deltaTheta();
        double h = ArchBlockData.DEPTH_HALF;
        Vec3 v = depth(a);
        Vec3 i0 = polar(a, t0, a.innerRadius());
        Vec3 o0 = polar(a, t0, a.outerRadius());
        Vec3 i1 = polar(a, t1, a.innerRadius());
        Vec3 o1 = polar(a, t1, a.outerRadius());
        return new Vec3[]{
                i0.subtract(v.scale(h)), o0.subtract(v.scale(h)), o1.subtract(v.scale(h)), i1.subtract(v.scale(h)),
                i0.add(v.scale(h)), o0.add(v.scale(h)), o1.add(v.scale(h)), i1.add(v.scale(h))
        };
    }

    /** Subdivisions per voussoir along the arc: the curved faces (inner/outer arc and the two
     *  depth faces) are split into this many quads, every corner sitting exactly on the circle,
     *  so the arch renders and collides as a smooth curve instead of flat facets. */
    public static final int ARC_SUBDIVISIONS = 8;

    /** One tessellated face of a voussoir: 4 world-space corners with outward winding, the
     *  texture tile coordinates at each corner (u along the arc, v along the depth / radius)
     *  and the face kind (see {@link #FACE_INNER}). */
    public record WedgeFace(Vec3[] corners, double[] u, double[] v, int kind) {
    }

    /** Face kinds (map to the vanilla sprite: inner = bottom, outer = top, everything else
     *  sides). */
    public static final int FACE_INNER = 0, FACE_OUTER = 1, FACE_START = 2, FACE_END = 3,
            FACE_BACK = 4, FACE_FRONT = 5;

    /**
     * The wedge surface as quads, each wound so its geometric normal points OUTWARD. The
     * inner/outer arc faces and the two depth faces are split into {@link #ARC_SUBDIVISIONS}
     * quads along the arc (every corner exactly on the circle), and the two radial faces stay
     * single flat quads. The shared end-radial edge is nudged a hair inward so it cannot
     * z-fight with the next voussoir's coincident start face.
     */
    public static List<WedgeFace> wedgeFaces(ArchBlockData a) {
        int n = ARC_SUBDIVISIONS;
        double t0 = a.thetaStart();
        double t1 = t0 + a.deltaTheta();
        double h = ArchBlockData.DEPTH_HALF;
        Vec3 v = depth(a);
        double ri = a.innerRadius();
        double ro = a.outerRadius();
        double rm = a.radius();
        Vec3[][] inner = new Vec3[n + 1][2];
        Vec3[][] outer = new Vec3[n + 1][2];
        for (int i = 0; i <= n; i++) {
            double t = t0 + (t1 - t0) * i / n;
            Vec3 pi = polar(a, t, ri);
            Vec3 po = polar(a, t, ro);
            inner[i][0] = pi.subtract(v.scale(h));
            inner[i][1] = pi.add(v.scale(h));
            outer[i][0] = po.subtract(v.scale(h));
            outer[i][1] = po.add(v.scale(h));
        }
        // The end-radial edge is shared with the next voussoir's start edge; nudge it inward
        // (along -outward, which for the arch's end face is +tangent) so the coincident faces
        // cannot z-fight.
        Vec3 inward = tangent(a, t1);
        for (int i : new int[]{n}) {
            inner[i][0] = inner[i][0].add(inward.scale(0.0005));
            inner[i][1] = inner[i][1].add(inward.scale(0.0005));
            outer[i][0] = outer[i][0].add(inward.scale(0.0005));
            outer[i][1] = outer[i][1].add(inward.scale(0.0005));
        }

        List<WedgeFace> faces = new ArrayList<>(4 * n + 2);
        for (int i = 0; i < n; i++) {
            double ri0 = ri * a.deltaTheta() * i / n;
            double ri1 = ri * a.deltaTheta() * (i + 1) / n;
            double ro0 = ro * a.deltaTheta() * i / n;
            double ro1 = ro * a.deltaTheta() * (i + 1) / n;
            double rm0 = rm * a.deltaTheta() * i / n;
            double rm1 = rm * a.deltaTheta() * (i + 1) / n;
            // inner arc face (toward the circle center)
            faces.add(new WedgeFace(new Vec3[]{inner[i][0], inner[i + 1][0], inner[i + 1][1], inner[i][1]},
                    new double[]{ri0, ri1, ri1, ri0}, new double[]{0, 0, 1, 1}, FACE_INNER));
            // outer arc face (away from the circle center)
            faces.add(new WedgeFace(new Vec3[]{outer[i][0], outer[i][1], outer[i + 1][1], outer[i + 1][0]},
                    new double[]{ro0, ro0, ro1, ro1}, new double[]{0, 1, 1, 0}, FACE_OUTER));
            // back depth face (-v)
            faces.add(new WedgeFace(new Vec3[]{inner[i][0], outer[i][0], outer[i + 1][0], inner[i + 1][0]},
                    new double[]{rm0, rm0, rm1, rm1}, new double[]{0, 1, 1, 0}, FACE_BACK));
            // front depth face (+v)
            faces.add(new WedgeFace(new Vec3[]{inner[i][1], inner[i + 1][1], outer[i + 1][1], outer[i][1]},
                    new double[]{rm0, rm1, rm1, rm0}, new double[]{0, 0, 1, 1}, FACE_FRONT));
        }
        // start radial face (flat): A, E, F, B
        faces.add(new WedgeFace(new Vec3[]{inner[0][0], inner[0][1], outer[0][1], outer[0][0]},
                new double[]{0, 0, 1, 1}, new double[]{0, 1, 1, 0}, FACE_START));
        // end radial face (flat, nudged): D, C, G, H
        faces.add(new WedgeFace(new Vec3[]{inner[n][0], outer[n][0], outer[n][1], inner[n][1]},
                new double[]{0, 1, 1, 0}, new double[]{0, 0, 1, 1}, FACE_END));
        return faces;
    }

    /**
     * The triangles of the closed wedge surface (2 per face), in world space. Used verbatim for
     * the exact-mesh collision and raycast paths - the arc faces are tessellated, so the wedge
     * collides (and is hit by placement/raycasts) as a smooth curve.
     */
    public static List<MeshCollisionShape.Tri> wedgeTriangles(ArchBlockData a) {
        List<MeshCollisionShape.Tri> tris = new ArrayList<>(4 * ARC_SUBDIVISIONS + 4);
        for (WedgeFace face : wedgeFaces(a)) {
            Vec3[] c = face.corners();
            tris.add(new MeshCollisionShape.Tri(
                    c[0].x, c[0].y, c[0].z, c[1].x, c[1].y, c[1].z, c[2].x, c[2].y, c[2].z));
            tris.add(new MeshCollisionShape.Tri(
                    c[0].x, c[0].y, c[0].z, c[2].x, c[2].y, c[2].z, c[3].x, c[3].y, c[3].z));
        }
        return tris;
    }

    /**
     * The textured quads of the wedge for the renderer. Vertex positions are WORLD space (the
     * arch renderer does not translate the pose to a cell) and the curved faces are tessellated
     * along the arc so the band renders as a smooth curve. Texture mapping approximates the
     * block texture per face (top texture on the outer arc, bottom on the inner arc, side
     * textures on the joints and ends), tiling once per meter (see {@link #putVertex}).
     */
    public static List<BakedQuad> wedgeQuads(ArchBlockData a, BlockState state) {
        Direction[] spriteDirs = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.NORTH,
                Direction.NORTH, Direction.NORTH};
        List<BakedQuad> quads = new ArrayList<>(4 * ARC_SUBDIVISIONS + 2);
        for (WedgeFace face : wedgeFaces(a)) {
            Vec3[] c = face.corners();
            TextureAtlasSprite sprite = spriteFor(state, spriteDirs[face.kind()]);
            if (sprite == null) {
                continue;
            }
            Direction cull = Direction.getNearest(
                    (float) outwardNormal(c[0], c[1], c[2]).x,
                    (float) outwardNormal(c[0], c[1], c[2]).y,
                    (float) outwardNormal(c[0], c[1], c[2]).z);
            int[] vertices = new int[32];
            putVertex(vertices, 0, c[0], (float) face.u()[0], (float) face.v()[0], sprite);
            putVertex(vertices, 1, c[1], (float) face.u()[1], (float) face.v()[1], sprite);
            putVertex(vertices, 2, c[2], (float) face.u()[2], (float) face.v()[2], sprite);
            putVertex(vertices, 3, c[3], (float) face.u()[3], (float) face.v()[3], sprite);
            quads.add(new BakedQuad(vertices, -1, cull, sprite, true));
        }
        return quads;
    }

    /**
     * Packs one BLOCK-format vertex (x/y/z floats, white color, sprite UV in tiles, empty
     * light/normal - the renderer recomputes light and the normal from the geometry). The tile
     * coordinates are wrapped into [0,1) so the texture TILES once per meter of face instead of
     * extrapolating past the sprite's atlas rectangle (getU/getV are linear, so a UV of 1.2
     * would sample the neighboring texture in the atlas and show a broken, stretched look).
     *
     * <p>TEMPORARY DEBUG (UV_DEBUG): the block texture is replaced by the atlas's missingno
     * texture - a hard-edged magenta/black checkerboard - so the true UV scale on the wedges is
     * unmistakable (vertex colours would interpolate to soft gradients). One 16px checker equals
     * one meter of face when the mapping is 1 tile per meter.
     */
    private static final boolean UV_DEBUG = true;

    static void putVertex(int[] out, int index, Vec3 p, float uTiles, float vTiles,
                                  TextureAtlasSprite sprite) {
        int o = index * 8;
        if (UV_DEBUG) {
            sprite = Minecraft.getInstance().getTextureAtlas(
                    net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                    .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("missingno"));
        }
        out[o] = Float.floatToRawIntBits((float) p.x);
        out[o + 1] = Float.floatToRawIntBits((float) p.y);
        out[o + 2] = Float.floatToRawIntBits((float) p.z);
        out[o + 3] = 0xFFFFFFFF; // white; tint/shade applied by the renderer
        out[o + 4] = Float.floatToRawIntBits(sprite.getU(uTiles - (float) Math.floor(uTiles)));
        out[o + 5] = Float.floatToRawIntBits(sprite.getV(vTiles - (float) Math.floor(vTiles)));
        out[o + 6] = 0;
        out[o + 7] = 0;
    }

    /** Computes the outward unit normal of the quad (p0,p1,p2) by its winding. */
    private static Vec3 outwardNormal(Vec3 p0, Vec3 p1, Vec3 p2) {
        Vec3 e1 = p1.subtract(p0);
        Vec3 e2 = p2.subtract(p0);
        Vec3 n = e1.cross(e2);
        double len = n.length();
        return len < 1.0E-9 ? new Vec3(0, 1, 0) : n.scale(1.0 / len);
    }

    @Nullable
    static TextureAtlasSprite spriteFor(BlockState state, Direction face) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            BakedModel model = minecraft.getModelManager().getBlockModelShaper().getBlockModel(state);
            if (model != null) {
                RandomSource random = RandomSource.create();
                List<BakedQuad> quads = model.getQuads(state, face, random);
                if (!quads.isEmpty() && quads.get(0).getSprite() != null) {
                    return quads.get(0).getSprite();
                }
                if (model.getParticleIcon() != null) {
                    return model.getParticleIcon();
                }
            }
        } catch (Throwable ignored) {
            // Missing/unloaded model: fall through to the atlas missing texture.
        }
        return minecraft.getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace("missingno"));
    }
}
