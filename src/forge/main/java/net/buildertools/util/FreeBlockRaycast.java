package net.buildertools.util;

import net.buildertools.server.RotationStore;
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
