package net.buildertools.util;

import net.buildertools.collision.RotatedCollisionProvider;
import net.buildertools.server.RotationStore;
import net.buildertools.util.ArchGeometry;
import net.buildertools.util.EllipseGeometry;
import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * Raycasts the mod's rotated-block layer. The client supplies the same baked-model triangles that
 * are rendered, so selection, placement and the Laser Tool hit the visible surface instead of the
 * axis-aligned cell or its bounding-box envelope. A server-safe oriented AABB fallback is retained
 * for dedicated servers before a client model provider is available.
 */
public final class FreeBlockRaycast {
    public record Hit(BlockPos cell, Vec3 point, Direction side, double distSq) {
    }

    private FreeBlockRaycast() {
    }

    public static Hit raycast(BlockGetter level, Vec3 from, Vec3 to) {
        AABB rayBox = new AABB(from, to).inflate(0.25);
        double bestT = Double.POSITIVE_INFINITY;
        Hit bestHit = null;
        for (Map.Entry<BlockPos, RotationData> e : RotationStore.getInBox(level, rayBox)) {
            BlockPos pos = e.getKey();
            RotationData rot = e.getValue();

            // Arch / ellipse voussoirs raycast against their exact wedge mesh (deterministic
            // geometry).
            if (rot.arch() != null) {
                for (MeshCollisionShape.Tri triangle : ArchGeometry.wedgeTriangles(rot.arch())) {
                    double[] hit = rayTriangle(from, to, triangle);
                    if (hit == null || hit[0] >= bestT) {
                        continue;
                    }
                    bestT = hit[0];
                    Vec3 point = from.add(to.subtract(from).scale(hit[0]));
                    bestHit = new Hit(pos, point, triangleSide(triangle, from, to),
                            from.distanceToSqr(point));
                }
                continue;
            }
            if (rot.ellipse() != null) {
                for (MeshCollisionShape.Tri triangle : EllipseGeometry.wedgeTriangles(rot.ellipse())) {
                    double[] hit = rayTriangle(from, to, triangle);
                    if (hit == null || hit[0] >= bestT) {
                        continue;
                    }
                    bestT = hit[0];
                    Vec3 point = from.add(to.subtract(from).scale(hit[0]));
                    bestHit = new Hit(pos, point, triangleSide(triangle, from, to),
                            from.distanceToSqr(point));
                }
                continue;
            }

            // On the client, this is the exact mesh used by RotatedBlockRenderer. Do not fall back
            // to the AABB when a model exists but a ray misses it: the AABB corners are precisely
            // the invisible XYZ-oriented hitbox this raycast must not expose.
            List<MeshCollisionShape.Tri> triangles = RotatedCollisionProvider.triangles(rot, pos, level);
            if (triangles != null) {
                for (MeshCollisionShape.Tri triangle : triangles) {
                    double[] hit = rayTriangle(from, to, triangle);
                    if (hit == null || hit[0] >= bestT) {
                        continue;
                    }
                    bestT = hit[0];
                    Vec3 point = from.add(to.subtract(from).scale(hit[0]));
                    bestHit = new Hit(pos, point, triangleSide(triangle, from, to),
                            from.distanceToSqr(point));
                }
                continue;
            }

            // Dedicated-server/model-loading fallback. Build the same oriented triangle surface
            // used by the movement fallback instead of raycasting the rotated shape's enclosing
            // AABB. The latter reintroduced the invisible XYZ-grid cube around stairs, slabs and
            // other partial blocks whenever a baked model was not ready yet.
            var base = rot.state().getCollisionShape(level, pos);
            if (base == null || base.isEmpty()) {
                continue;
            }
            Vec3 c = rot.center(pos);
            MeshCollisionShape exact = MeshCollisionShape.fromVoxelShape(base, c.x, c.y, c.z,
                    rot.yaw(), rot.pitch());
            final double[] candidateT = {bestT};
            final Hit[] candidateHit = {null};
            exact.forEachTriangle(triangle -> {
                double[] hit = rayTriangle(from, to, triangle);
                if (hit == null || hit[0] >= candidateT[0]) {
                    return;
                }
                candidateT[0] = hit[0];
                Vec3 point = from.add(to.subtract(from).scale(hit[0]));
                candidateHit[0] = new Hit(pos, point, triangleSide(triangle, from, to),
                        from.distanceToSqr(point));
            });
            if (candidateHit[0] != null) {
                bestT = candidateT[0];
                bestHit = candidateHit[0];
            }
        }
        return bestHit;
    }

    /** Möller-Trumbore segment/triangle intersection, returning the segment parameter or null. */
    private static double[] rayTriangle(Vec3 from, Vec3 to, MeshCollisionShape.Tri t) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double e1x = t.bx - t.ax, e1y = t.by - t.ay, e1z = t.bz - t.az;
        double e2x = t.cx - t.ax, e2y = t.cy - t.ay, e2z = t.cz - t.az;
        double px = dy * e2z - dz * e2y;
        double py = dz * e2x - dx * e2z;
        double pz = dx * e2y - dy * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < 1.0E-10) {
            return null;
        }
        double inverseDet = 1.0 / det;
        double tx = from.x - t.ax, ty = from.y - t.ay, tz = from.z - t.az;
        double u = (tx * px + ty * py + tz * pz) * inverseDet;
        if (u < -1.0E-8 || u > 1.0 + 1.0E-8) {
            return null;
        }
        double qx = ty * e1z - tz * e1y;
        double qy = tz * e1x - tx * e1z;
        double qz = tx * e1y - ty * e1x;
        double v = (dx * qx + dy * qy + dz * qz) * inverseDet;
        if (v < -1.0E-8 || u + v > 1.0 + 1.0E-8) {
            return null;
        }
        double parameter = (e2x * qx + e2y * qy + e2z * qz) * inverseDet;
        if (parameter < -1.0E-8 || parameter > 1.0 + 1.0E-8) {
            return null;
        }
        return new double[]{Math.max(0.0, parameter)};
    }

    /** Returns the triangle's outward-facing nearest cardinal side, oriented toward the ray. */
    private static Direction triangleSide(MeshCollisionShape.Tri t, Vec3 from, Vec3 to) {
        double nx = (t.by - t.ay) * (t.cz - t.az) - (t.bz - t.az) * (t.cy - t.ay);
        double ny = (t.bz - t.az) * (t.cx - t.ax) - (t.bx - t.ax) * (t.cz - t.az);
        double nz = (t.bx - t.ax) * (t.cy - t.ay) - (t.by - t.ay) * (t.cx - t.ax);
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-10) {
            return Direction.UP;
        }
        Vec3 ray = to.subtract(from);
        if (nx * ray.x + ny * ray.y + nz * ray.z > 0.0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        return Direction.getNearest(nx / len, ny / len, nz / len);
    }

}
