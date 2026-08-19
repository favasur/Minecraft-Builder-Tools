package net.buildertools.util;

import com.mojang.math.Transformation;
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
}
