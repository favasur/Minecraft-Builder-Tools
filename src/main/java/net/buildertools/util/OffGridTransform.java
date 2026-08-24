package net.buildertools.util;

import com.mojang.math.Transformation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared transforms for rotated and off-grid block placement. */
public final class OffGridTransform {
    public static final float HALF = 0.5f;

    private OffGridTransform() {
    }

    /** Rotation used by both the display renderer and the custom block renderer. */
    public static Quaternionf rotation(float yawDeg, float pitchDeg) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDeg))
                .mul(new Quaternionf().rotateX((float) Math.toRadians(pitchDeg)));
    }

    /** Camera-facing angles for a center billboard. */
    public static float[] billboardAngles(Vec3 center, Vec3 camera) {
        double dx = center.x - camera.x;
        double dy = center.y - camera.y;
        double dz = center.z - camera.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new float[]{
                (float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) -Math.toDegrees(Math.atan2(dy, Math.max(horizontal, 1.0E-4)))
        };
    }

    /** Display transformation that rotates a model around its local center. */
    public static Transformation transformation(float yawDeg, float pitchDeg) {
        Quaternionf rot = rotation(yawDeg, pitchDeg);
        Vector3f center = new Vector3f(HALF, HALF, HALF);
        Vector3f translation = new Vector3f(center)
                .sub(rot.transform(center, new Vector3f()));
        return new Transformation(translation, rot,
                new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf());
    }

    /** True when two block collision shapes penetrate after their independent rotations. */
    public static boolean modelsOverlap(double cx1, double cy1, double cz1, float yaw1, float pitch1,
                                        VoxelShape shape1, double cx2, double cy2, double cz2,
                                        float yaw2, float pitch2, VoxelShape shape2) {
        if (shape1.isEmpty() || shape2.isEmpty()) {
            return false;
        }
        java.util.List<AABB> boxes1 = new java.util.ArrayList<>();
        shape1.forAllBoxes((x0, y0, z0, x1, y1, z1) -> boxes1.add(new AABB(x0, y0, z0, x1, y1, z1)));
        java.util.List<AABB> boxes2 = new java.util.ArrayList<>();
        shape2.forAllBoxes((x0, y0, z0, x1, y1, z1) -> boxes2.add(new AABB(x0, y0, z0, x1, y1, z1)));
        for (AABB box1 : boxes1) {
            for (AABB box2 : boxes2) {
                if (orientedBoxesOverlap(cx1, cy1, cz1, yaw1, pitch1, box1,
                        cx2, cy2, cz2, yaw2, pitch2, box2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean orientedBoxesOverlap(double cx1, double cy1, double cz1, float yaw1, float pitch1,
                                                AABB shape1, double cx2, double cy2, double cz2,
                                                float yaw2, float pitch2, AABB shape2) {
        Quaternionf rot1 = rotation(yaw1, pitch1);
        Quaternionf rot2 = rotation(yaw2, pitch2);
        Vector3f a1x = rot1.transform(new Vector3f(1, 0, 0), new Vector3f());
        Vector3f a1y = rot1.transform(new Vector3f(0, 1, 0), new Vector3f());
        Vector3f a1z = rot1.transform(new Vector3f(0, 0, 1), new Vector3f());
        Vector3f a2x = rot2.transform(new Vector3f(1, 0, 0), new Vector3f());
        Vector3f a2y = rot2.transform(new Vector3f(0, 1, 0), new Vector3f());
        Vector3f a2z = rot2.transform(new Vector3f(0, 0, 1), new Vector3f());
        float h1x = (float) ((shape1.maxX - shape1.minX) / 2.0);
        float h1y = (float) ((shape1.maxY - shape1.minY) / 2.0);
        float h1z = (float) ((shape1.maxZ - shape1.minZ) / 2.0);
        float h2x = (float) ((shape2.maxX - shape2.minX) / 2.0);
        float h2y = (float) ((shape2.maxY - shape2.minY) / 2.0);
        float h2z = (float) ((shape2.maxZ - shape2.minZ) / 2.0);
        Vector3f delta = new Vector3f((float) (cx2 - cx1), (float) (cy2 - cy1), (float) (cz2 - cz1));
        Vector3f[] axes = {
                a1x, a1y, a1z, a2x, a2y, a2z,
                a1x.cross(a2x, new Vector3f()), a1x.cross(a2y, new Vector3f()), a1x.cross(a2z, new Vector3f()),
                a1y.cross(a2x, new Vector3f()), a1y.cross(a2y, new Vector3f()), a1y.cross(a2z, new Vector3f()),
                a1z.cross(a2x, new Vector3f()), a1z.cross(a2y, new Vector3f()), a1z.cross(a2z, new Vector3f())
        };
        for (Vector3f axis : axes) {
            double length = axis.length();
            if (length < 1.0E-5) {
                continue;
            }
            Vector3f normal = new Vector3f(axis).div((float) length);
            double radius1 = h1x * Math.abs(a1x.dot(normal))
                    + h1y * Math.abs(a1y.dot(normal))
                    + h1z * Math.abs(a1z.dot(normal));
            double radius2 = h2x * Math.abs(a2x.dot(normal))
                    + h2y * Math.abs(a2y.dot(normal))
                    + h2z * Math.abs(a2z.dot(normal));
            if (Math.abs(delta.dot(normal)) > radius1 + radius2 - 1.0E-3) {
                return false;
            }
        }
        return true;
    }

    /** Conservative world-space AABB around a rotated local shape. */
    public static AABB boxAround(double cx, double cy, double cz,
                                 float yawDeg, float pitchDeg, AABB shape) {
        Quaternionf rot = rotation(yawDeg, pitchDeg);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{shape.minX, shape.maxX}) {
            for (double y : new double[]{shape.minY, shape.maxY}) {
                for (double z : new double[]{shape.minZ, shape.maxZ}) {
                    Vector3f point = rot.transform(new Vector3f(
                            (float) (x - HALF), (float) (y - HALF), (float) (z - HALF)),
                            new Vector3f());
                    minX = Math.min(minX, cx + point.x);
                    minY = Math.min(minY, cy + point.y);
                    minZ = Math.min(minZ, cz + point.z);
                    maxX = Math.max(maxX, cx + point.x);
                    maxY = Math.max(maxY, cy + point.y);
                    maxZ = Math.max(maxZ, cz + point.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Returns the world direction of the local face hit by a ray through a rotated block. */
    public static Vec3 rotatedGridOffset(Quaternionf rot, Vec3 center, AABB shape,
                                         Vec3 eye, Vec3 dir) {
        Quaternionf inverse = new Quaternionf(rot).conjugate();
        Vector3f origin = inverse.transform(new Vector3f(
                (float) (eye.x - center.x), (float) (eye.y - center.y),
                (float) (eye.z - center.z)), new Vector3f());
        Vector3f direction = inverse.transform(
                new Vector3f((float) dir.x, (float) dir.y, (float) dir.z), new Vector3f());
        double minX = shape.minX - HALF;
        double maxX = shape.maxX - HALF;
        double minY = shape.minY - HALF;
        double maxY = shape.maxY - HALF;
        double minZ = shape.minZ - HALF;
        double maxZ = shape.maxZ - HALF;

        double entry = 0.0;
        double exit = Double.POSITIVE_INFINITY;
        int entryAxis = -1;
        boolean entryLow = false;
        for (int axis = 0; axis < 3; axis++) {
            double originValue = axis == 0 ? origin.x : axis == 1 ? origin.y : origin.z;
            double directionValue = axis == 0 ? direction.x : axis == 1 ? direction.y : direction.z;
            double low = axis == 0 ? minX : axis == 1 ? minY : minZ;
            double high = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
            if (Math.abs(directionValue) < 1.0E-8) {
                if (originValue < low || originValue > high) {
                    return null;
                }
                continue;
            }
            double lowT = (low - originValue) / directionValue;
            double highT = (high - originValue) / directionValue;
            boolean lowEntry = lowT < highT;
            if (!lowEntry) {
                double tmp = lowT;
                lowT = highT;
                highT = tmp;
            }
            if (lowT > entry) {
                entry = lowT;
                entryAxis = axis;
                entryLow = lowEntry;
            }
            exit = Math.min(exit, highT);
            if (entry > exit) {
                return null;
            }
        }
        if (entryAxis < 0) {
            return null;
        }
        Vector3f face = switch (entryAxis) {
            case 0 -> new Vector3f(entryLow ? -1 : 1, 0, 0);
            case 1 -> new Vector3f(0, entryLow ? -1 : 1, 0);
            default -> new Vector3f(0, 0, entryLow ? -1 : 1);
        };
        Vector3f world = rot.transform(face, new Vector3f());
        return new Vec3(world.x, world.y, world.z);
    }
}
