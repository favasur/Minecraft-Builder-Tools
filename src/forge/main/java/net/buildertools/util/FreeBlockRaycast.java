package net.buildertools.util;

import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import net.buildertools.server.RotationStore;
import net.buildertools.util.EllipseGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Raycasts the mod's rotated-block layer. Each rotated block is tested against its tight
 * world-space bounding box (the AABB that encloses its rotated model), returning the nearest hit
 * point, the world face it entered through and the owning cell - the same technique the
 * PlaceAnywhere mod uses for its free blocks.
 */
public final class FreeBlockRaycast {
    public record Hit(BlockPos cell, Vec3 point, Direction side, double distSq) {
    }

    private FreeBlockRaycast() {
    }

    public static Hit raycast(BlockGetter level, Vec3 from, Vec3 to) {
        AABB rayBox = new AABB(from, to).inflate(0.25);
        double best = Double.POSITIVE_INFINITY;
        Hit bestHit = null;
        for (Map.Entry<BlockPos, RotationData> e : RotationStore.getInBox(level, rayBox)) {
            BlockPos pos = e.getKey();
            RotationData rot = e.getValue();

            // Arch / ellipse voussoirs raycast against their exact wedge mesh (deterministic
            // geometry).
            if (rot.arch() != null) {
                for (MeshCollisionShape.Tri triangle : ArchGeometry.wedgeTriangles(rot.arch())) {
                    double[] hit = rayTriangle(from, to, triangle);
                    if (hit == null || hit[0] >= best) {
                        continue;
                    }
                    best = hit[0];
                    Vec3 point = from.add(to.subtract(from).scale(hit[0]));
                    bestHit = new Hit(pos, point, triangleSide(triangle, from, to),
                            from.distanceToSqr(point));
                }
                continue;
            }
            if (rot.ellipse() != null) {
                for (MeshCollisionShape.Tri triangle : EllipseGeometry.wedgeTriangles(rot.ellipse())) {
                    double[] hit = rayTriangle(from, to, triangle);
                    if (hit == null || hit[0] >= best) {
                        continue;
                    }
                    best = hit[0];
                    Vec3 point = from.add(to.subtract(from).scale(hit[0]));
                    bestHit = new Hit(pos, point, triangleSide(triangle, from, to),
                            from.distanceToSqr(point));
                }
                continue;
            }

            Vec3 c = rot.center(pos);
            AABB shape = rot.state().getCollisionShape(level, pos).bounds();
            AABB box = OffGridTransform.boxAround(
                    c.x, c.y, c.z, rot.yaw(), rot.pitch(), shape);
            double[] t = rayAABB(from, to, box);
            if (t == null) {
                continue;
            }
            if (t[0] < best) {
                best = t[0];
                Vec3 point = from.add(to.subtract(from).scale(t[0]));
                bestHit = new Hit(pos, point, sideOf(box, point, t[1]), from.distanceToSqr(point));
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

    /** Returns { entryT, entryAxis } (axis 0/1/2 = x/y/z) of the ray/AABB hit, or null. */
    private static double[] rayAABB(Vec3 from, Vec3 to, AABB box) {
        Vec3 dir = to.subtract(from);
        double tmin = 0.0, tmax = 1.0;
        int entryAxis = -1;
        for (int i = 0; i < 3; i++) {
            double o = i == 0 ? from.x : i == 1 ? from.y : from.z;
            double d = i == 0 ? dir.x : i == 1 ? dir.y : dir.z;
            double lo = i == 0 ? box.minX : i == 1 ? box.minY : box.minZ;
            double hi = i == 0 ? box.maxX : i == 1 ? box.maxY : box.maxZ;
            if (Math.abs(d) < 1.0E-8) {
                if (o < lo || o > hi) {
                    return null;
                }
                continue;
            }
            double tLow = (lo - o) / d;
            double tHigh = (hi - o) / d;
            if (tLow > tHigh) {
                double tmp = tLow;
                tLow = tHigh;
                tHigh = tmp;
            }
            if (tLow > tmin) {
                tmin = tLow;
                entryAxis = i;
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
        return new double[]{tmin, entryAxis};
    }

    /** The world direction of the AABB face the entry point lies on. */
    private static Direction sideOf(AABB box, Vec3 point, double axis) {
        double ex = Math.min(Math.abs(point.x - box.minX), Math.abs(point.x - box.maxX));
        double ey = Math.min(Math.abs(point.y - box.minY), Math.abs(point.y - box.maxY));
        double ez = Math.min(Math.abs(point.z - box.minZ), Math.abs(point.z - box.maxZ));
        double best = Math.min(ex, Math.min(ey, ez));
        if (best == ex) {
            return Math.abs(point.x - box.minX) <= Math.abs(point.x - box.maxX) ? Direction.WEST : Direction.EAST;
        }
        if (best == ey) {
            return Math.abs(point.y - box.minY) <= Math.abs(point.y - box.maxY) ? Direction.DOWN : Direction.UP;
        }
        return Math.abs(point.z - box.minZ) <= Math.abs(point.z - box.maxZ) ? Direction.NORTH : Direction.SOUTH;
    }
}
