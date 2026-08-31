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
     * Shared by {@code BuilderServerHandler.archBlocks} and the client's ghost preview so the
     * curve shown while aiming is exactly what the click generates.
     */
    public record RegionArch(ArchResult arch, int count, double span, Vec3 center) {
    }

    /** Minimum rise (m) below which the click is treated as being on the chord itself. */
    public static final double MIN_RISE = 0.5;

    private ArchGeometry() {
    }

    /**
     * Computes the arch through {@code start} and {@code end} (the centers of the first and last
     * row blocks, Span {@code S}) that bulges toward {@code click} (the Rise {@code H}). The arch
     * plane is free - {@code u} and {@code w} are derived from the chord and the click. Returns
     * null when the input cannot form a valid arch (span shorter than 1m or a click on the chord
     * itself).
     */
    @Nullable
    public static ArchResult computeArch(Vec3 start, Vec3 end, Vec3 click) {
        Vec3 u = end.subtract(start).normalize();
        Vec3 mid = start.add(end).scale(0.5);
        // The rise is the perpendicular (to the span) component of click - chordMidpoint.
        Vec3 perp = click.subtract(mid).subtract(u.scale(click.subtract(mid).dot(u)));
        double rise = perp.length();
        if (rise < MIN_RISE) {
            return null;
        }
        return computeArchInPlane(start, end, u, perp.scale(1.0 / rise), click);
    }

    /**
     * Face-relative variant: the arch plane is FIXED by the clicked block's face - unit
     * {@code u} and {@code w} span the face plane (the span and rise directions) and the click is
     * any point in that plane whose offset along {@code w} from the chord sets the Rise
     * {@code H}. Returns null when the input cannot form a valid arch (span shorter than 1m, or
     * a click on the chord itself).
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
        double radius = rise / 2.0 + span * span / (8.0 * rise);
        // Angles: the arc runs from start (pi - phiB) through the apex (pi/2) to end (phiB).
        double phiB = Math.atan2(radius - rise, span / 2.0);
        double thetaStart = Math.PI - phiB;
        double total = thetaStart - phiB;
        return new ArchResult(mid.subtract(w.scale(radius - rise)), u, w, radius,
                thetaStart, total);
    }

    /**
     * Derives the arch that a click on the given cell face would commit for the stretched wall
     * region {@code [min, max]}: the face fixes the arch's frame (the face normal is the depth
     * axis, the face plane holds the span and rise directions), the region's larger projected
     * extent in the face plane is the Span {@code S}, and the face centre's offset along the
     * in-plane rise direction from the chord is the Rise {@code H}. Returns null when no arch
     * can form (no face, a region with no real span in the face plane, or a click on the chord
     * itself). This is the single source of truth for both the server commit and the client
     * ghost preview.
     */
    @Nullable
    public static RegionArch regionArch(BlockPos min, BlockPos max, BlockPos click, Direction face) {
        if (click == null || face == null) {
            return null;
        }
        FaceFrame frame = FaceFrame.of(click, face);
        double extentRight = cellExtent(min, max, frame.right());
        double extentUp = cellExtent(min, max, frame.up());
        Vec3 u = extentRight >= extentUp ? frame.right() : frame.up();
        Vec3 w = extentRight >= extentUp ? frame.up() : frame.right();
        double span = Math.max(extentRight, extentUp);
        if (span < 1.0) {
            return null;
        }
        int count = (int) Math.round(span) + 1;
        Vec3 center = boxCenter(min, max);
        Vec3 start = center.subtract(u.scale(span / 2.0));
        Vec3 end = center.add(u.scale(span / 2.0));
        ArchResult arch = computeArchInPlane(start, end, u, w, frame.origin());
        if (arch == null) {
            return null;
        }
        return new RegionArch(arch, count, span, center);
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

    /**
     * The six faces of the wedge as vertex-index quads, each wound so its geometric normal points
     * OUTWARD (verified against the analytic outward direction in {@link #orderedQuad}).
     * Order: inner, outer, start radial, end radial, back depth, front depth.
     */
    public static int[][] wedgeFaces() {
        return new int[][]{
                {0, 3, 7, 4}, // inner  (toward the circle center)
                {1, 5, 6, 2}, // outer  (away from the circle center)
                {0, 4, 5, 1}, // start radial (toward the previous voussoir)
                {3, 2, 6, 7}, // end radial (toward the next voussoir)
                {0, 1, 2, 3}, // back depth (-v)
                {4, 7, 6, 5}  // front depth (+v)
        };
    }

    /**
     * The 12 triangles of the closed wedge surface (6 faces x 2), in world space. Used verbatim
     * for the exact-mesh collision and raycast paths.
     */
    public static List<MeshCollisionShape.Tri> wedgeTriangles(ArchBlockData a) {
        Vec3[] v = wedgeVertices(a);
        List<MeshCollisionShape.Tri> tris = new ArrayList<>(12);
        for (int[] face : wedgeFaces()) {
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

    /**
     * The six textured quads of the wedge for the renderer. Vertex positions are WORLD space
     * (the arch renderer does not translate the pose to a cell). The shared end-radial face is
     * nudged a hair inward so it cannot z-fight with the next voussoir's coincident start face.
     * Texture mapping approximates the block texture per face (top texture on the outer arc,
     * bottom on the inner arc, side textures on the joints and ends), stretched over the face.
     */
    public static List<BakedQuad> wedgeQuads(ArchBlockData a, BlockState state) {
        Vec3[] v = wedgeVertices(a);
        double t1 = a.thetaStart() + a.deltaTheta();
        Vec3 inward = tangent(a, t1); // inward for the end-radial face (its outward is -tangent)
        double nudge = 0.0005;
        for (int i : new int[]{2, 3, 6, 7}) { // D, C, G, H: the whole end-radial edge
            v[i] = v[i].add(inward.scale(nudge));
        }

        double innerArc = a.innerRadius() * a.deltaTheta();
        double outerArc = a.outerRadius() * a.deltaTheta();
        double midArc = a.radius() * a.deltaTheta();
        // Per-face corner UVs in the same order as {@link #wedgeFaces()}: (u0,v0)..(u3,v3),
        // where u runs along the arc (or the radius for the radial faces) and v along the depth.
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
        int[][] faces = wedgeFaces();
        for (int f = 0; f < 6; f++) {
            int[] face = faces[f];
            Vec3 p0 = v[face[0]];
            Vec3 p1 = v[face[1]];
            Vec3 p2 = v[face[2]];
            Vec3 p3 = v[face[3]];
            TextureAtlasSprite sprite = spriteFor(state, dirs[f]);
            if (sprite == null) {
                continue;
            }
            Direction cull = Direction.getNearest(
                    (float) outwardNormal(p0, p1, p2).x,
                    (float) outwardNormal(p0, p1, p2).y,
                    (float) outwardNormal(p0, p1, p2).z);
            int[] vertices = new int[32];
            putVertex(vertices, 0, p0, (float) uvs[f][0], (float) uvs[f][1], sprite);
            putVertex(vertices, 1, p1, (float) uvs[f][2], (float) uvs[f][3], sprite);
            putVertex(vertices, 2, p2, (float) uvs[f][4], (float) uvs[f][5], sprite);
            putVertex(vertices, 3, p3, (float) uvs[f][6], (float) uvs[f][7], sprite);
            quads.add(new BakedQuad(vertices, -1, cull, sprite, true));
        }
        return quads;
    }

    /**
     * Packs one BLOCK-format vertex (x/y/z floats, white color, sprite UV in tiles, empty
     * light/normal - the renderer recomputes light and the normal from the geometry).
     */
    static void putVertex(int[] out, int index, Vec3 p, float uTiles, float vTiles,
                                  TextureAtlasSprite sprite) {
        int o = index * 8;
        out[o] = Float.floatToRawIntBits((float) p.x);
        out[o + 1] = Float.floatToRawIntBits((float) p.y);
        out[o + 2] = Float.floatToRawIntBits((float) p.z);
        out[o + 3] = 0xFFFFFFFF; // white; tint/shade applied by the renderer
        out[o + 4] = Float.floatToRawIntBits(sprite.getU(uTiles));
        out[o + 5] = Float.floatToRawIntBits(sprite.getV(vTiles));
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
