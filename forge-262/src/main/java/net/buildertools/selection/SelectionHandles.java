package net.buildertools.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The six "buttons" shown at the centre of each face of a selection (selection
 * handles). Each handle can be grabbed and dragged to expand or shrink that side of the region.
 */
public final class SelectionHandles {
    /** A handle on one face of the selection box. {@code positive} is true for the max side. */
    public record Handle(AABB box, Direction.Axis axis, boolean positive) {
    }

    // The handle is a flat "button plate" on the face (drag button style). Like Hytale, each
    // plate spans one third of its face's width and height, so it scales with the selection.
    private static final double PLATE_D = 0.16; // half depth through the face

    private SelectionHandles() {
    }

    /** Returns the six face handles of the given selection region. */
    public static List<Handle> handles(BlockPos min, BlockPos max) {
        double sizeX = max.getX() - min.getX() + 1.0;
        double sizeY = max.getY() - min.getY() + 1.0;
        double sizeZ = max.getZ() - min.getZ() + 1.0;
        double cx = (min.getX() + max.getX() + 1.0) / 2.0;
        double cy = (min.getY() + max.getY() + 1.0) / 2.0;
        double cz = (min.getZ() + max.getZ() + 1.0) / 2.0;
        // Half of the one-third span on either side of the face centre.
        List<Handle> list = new ArrayList<>(6);
        list.add(new Handle(plateX(min.getX(), cy, cz, sizeY / 6.0, sizeZ / 6.0), Direction.Axis.X, false));
        list.add(new Handle(plateX(max.getX() + 1, cy, cz, sizeY / 6.0, sizeZ / 6.0), Direction.Axis.X, true));
        list.add(new Handle(plateY(cx, min.getY(), cz, sizeX / 6.0, sizeZ / 6.0), Direction.Axis.Y, false));
        list.add(new Handle(plateY(cx, max.getY() + 1, cz, sizeX / 6.0, sizeZ / 6.0), Direction.Axis.Y, true));
        list.add(new Handle(plateZ(cx, cy, min.getZ(), sizeX / 6.0, sizeY / 6.0), Direction.Axis.Z, false));
        list.add(new Handle(plateZ(cx, cy, max.getZ() + 1, sizeX / 6.0, sizeY / 6.0), Direction.Axis.Z, true));
        return list;
    }

    private static AABB plateX(double x, double y, double z, double halfH, double halfW) {
        return new AABB(x - PLATE_D, y - halfH, z - halfW, x + PLATE_D, y + halfH, z + halfW);
    }

    private static AABB plateY(double x, double y, double z, double halfW, double halfD) {
        return new AABB(x - halfW, y - PLATE_D, z - halfD, x + halfW, y + PLATE_D, z + halfD);
    }

    private static AABB plateZ(double x, double y, double z, double halfW, double halfH) {
        return new AABB(x - halfW, y - halfH, z - PLATE_D, x + halfW, y + halfH, z + PLATE_D);
    }

    /** A handle together with the distance along the ray at which it was hit. */
    public record Hit(Handle handle, double t) {
    }

    /**
     * Returns the handle hit by the given ray, or null. Only handles whose hit distance is below
     * {@code maxT} (typically the distance to the block under the crosshair) are considered.
     */
    public static Hit raycast(List<Handle> handles, Vec3 origin, Vec3 dir, double maxT) {
        Handle best = null;
        double bestT = maxT;
        for (Handle handle : handles) {
            double t = rayBox(origin, dir, handle.box());
            if (t >= 0.0 && t < bestT) {
                best = handle;
                bestT = t;
            }
        }
        return best != null ? new Hit(best, bestT) : null;
    }

    /** Ray vs AABB intersection (slab method). Returns the entry distance, or -1 on a miss. */
    private static double rayBox(Vec3 origin, Vec3 dir, AABB box) {
        double tMin = 0.0;
        double tMax = Double.MAX_VALUE;

        if (Math.abs(dir.x) < 1.0E-8) {
            if (origin.x < box.minX || origin.x > box.maxX) {
                return -1.0;
            }
        } else {
            double t1 = (box.minX - origin.x) / dir.x;
            double t2 = (box.maxX - origin.x) / dir.x;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        if (Math.abs(dir.y) < 1.0E-8) {
            if (origin.y < box.minY || origin.y > box.maxY) {
                return -1.0;
            }
        } else {
            double t1 = (box.minY - origin.y) / dir.y;
            double t2 = (box.maxY - origin.y) / dir.y;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        if (Math.abs(dir.z) < 1.0E-8) {
            if (origin.z < box.minZ || origin.z > box.maxZ) {
                return -1.0;
            }
        } else {
            double t1 = (box.minZ - origin.z) / dir.z;
            double t2 = (box.maxZ - origin.z) / dir.z;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }

        return tMin <= tMax ? tMin : -1.0;
    }
}
