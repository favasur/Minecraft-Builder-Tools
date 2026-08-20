package net.buildertools.util;

import com.mojang.math.Transformation;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Shared math for off-grid blocks. A {@link net.minecraft.world.entity.Display.BlockDisplay}
 * renders its block model spanning 0..1 from the entity's position, so the entity is spawned at
 * the cell corner and the transformation rotates the model around the cell center
 * (0.5, 0.5, 0.5). The block therefore sits exactly on the vanilla grid while it spins in place,
 * Hytale-style. Yaw turns around the world Y axis (horizontal), pitch around the model's X axis
 * (vertical tilt).
 */
public final class OffGridTransform {
    public static final float HALF = 0.5f;

    private OffGridTransform() {
    }

    /**
     * Rotation applied to the model: yaw around Y (world), then pitch around X. The yaw is
     * negated to match the client's cursor-angle convention (0 at +X, counter-clockwise).
     */
    public static Quaternionf rotation(float yawDeg, float pitchDeg) {
        return new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDeg))
                .mul(new Quaternionf().rotateX((float) Math.toRadians(pitchDeg)));
    }

    /**
     * The display transformation for a block whose entity sits at the cell corner: M(p) = R·p + t
     * with t = c - R·c and c = (0.5, 0.5, 0.5), so model points p in 0..1 land exactly on the
     * cell and the cube rotates around its own center.
     */
    public static Transformation transformation(float yawDeg, float pitchDeg) {
        Quaternionf rot = rotation(yawDeg, pitchDeg);
        Vector3f center = new Vector3f(HALF, HALF, HALF);
        Vector3f rotCenter = rot.transform(center, new Vector3f());
        Vector3f translation = new Vector3f(center).sub(rotCenter);
        return new Transformation(translation, rot, new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf());
    }

    /**
     * True when the ACTUAL rotated models of two off-grid blocks overlap (penetrate), not merely
     * touch. Uses the Separating Axis Theorem on the oriented boxes: two blocks placed flush
     * against each other (their models touching face-to-face) are allowed, while a block pushed
     * INTO another is rejected. The axis-aligned bounding boxes cannot be used here - a rotated
     * cube's AABB inflates at the corners, so flush-adjacent rotated blocks always look
     * overlapping even though the models just touch.
     */
    public static boolean modelsOverlap(double cx1, double cy1, double cz1, float yaw1, float pitch1, AABB shape1,
                                        double cx2, double cy2, double cz2, float yaw2, float pitch2, AABB shape2) {
        Quaternionf rot1 = rotation(yaw1, pitch1);
        Quaternionf rot2 = rotation(yaw2, pitch2);
        // Local (rotated) axes of each model.
        Vector3f a1x = rot1.transform(new Vector3f(1, 0, 0), new Vector3f());
        Vector3f a1y = rot1.transform(new Vector3f(0, 1, 0), new Vector3f());
        Vector3f a1z = rot1.transform(new Vector3f(0, 0, 1), new Vector3f());
        Vector3f a2x = rot2.transform(new Vector3f(1, 0, 0), new Vector3f());
        Vector3f a2y = rot2.transform(new Vector3f(0, 1, 0), new Vector3f());
        Vector3f a2z = rot2.transform(new Vector3f(0, 0, 1), new Vector3f());
        // Half-extents along each model's own axes.
        float h1x = (float) ((shape1.maxX - shape1.minX) / 2.0);
        float h1y = (float) ((shape1.maxY - shape1.minY) / 2.0);
        float h1z = (float) ((shape1.maxZ - shape1.minZ) / 2.0);
        float h2x = (float) ((shape2.maxX - shape2.minX) / 2.0);
        float h2y = (float) ((shape2.maxY - shape2.minY) / 2.0);
        float h2z = (float) ((shape2.maxZ - shape2.minZ) / 2.0);
        Vector3f delta = new Vector3f((float) (cx2 - cx1), (float) (cy2 - cy1), (float) (cz2 - cz1));

        // The 15 candidate separating axes: the 3 axes of each box plus their 9 cross products.
        Vector3f[] axes = {
                a1x, a1y, a1z, a2x, a2y, a2z,
                a1x.cross(a2x, new Vector3f()), a1x.cross(a2y, new Vector3f()), a1x.cross(a2z, new Vector3f()),
                a1y.cross(a2x, new Vector3f()), a1y.cross(a2y, new Vector3f()), a1y.cross(a2z, new Vector3f()),
                a1z.cross(a2x, new Vector3f()), a1z.cross(a2y, new Vector3f()), a1z.cross(a2z, new Vector3f())
        };
        for (Vector3f axis : axes) {
            double len = axis.length();
            if (len < 1.0E-5) {
                continue; // parallel axes: the cross product is degenerate, no separating power
            }
            Vector3f n = new Vector3f(axis).div((float) len);
            double r1 = h1x * Math.abs(a1x.dot(n)) + h1y * Math.abs(a1y.dot(n)) + h1z * Math.abs(a1z.dot(n));
            double r2 = h2x * Math.abs(a2x.dot(n)) + h2y * Math.abs(a2y.dot(n)) + h2z * Math.abs(a2z.dot(n));
            double dist = Math.abs(delta.dot(n));
            // A separating axis exists when the projected intervals are disjoint; a small
            // tolerance makes face-to-face touching count as clear, so flush placements pass.
            if (dist > r1 + r2 - 1.0E-3) {
                return false;
            }
        }
        return true;
    }

    /**
     * The world-space AABB of a block shape (in block-local 0..1 coordinates, as returned by
     * {@code BlockState#getCollisionShape}) rotated by the placement yaw/pitch around the model
     * center {@code (cx, cy, cz)}. This is the tight axis-aligned box that encloses the rotated
     * model - the same box the rendered display spans - so collision and visuals can never drift
     * apart.
     */
    public static AABB boxAround(double cx, double cy, double cz, float yawDeg, float pitchDeg, AABB shape) {
        Quaternionf rot = rotation(yawDeg, pitchDeg);
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{shape.minX, shape.maxX}) {
            for (double y : new double[]{shape.minY, shape.maxY}) {
                for (double z : new double[]{shape.minZ, shape.maxZ}) {
                    Vector3f p = rot.transform(
                            new Vector3f((float) (x - HALF), (float) (y - HALF), (float) (z - HALF)),
                            new Vector3f());
                    minX = Math.min(minX, cx + p.x);
                    minY = Math.min(minY, cy + p.y);
                    minZ = Math.min(minZ, cz + p.z);
                    maxX = Math.max(maxX, cx + p.x);
                    maxY = Math.max(maxY, cy + p.y);
                    maxZ = Math.max(maxZ, cz + p.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
