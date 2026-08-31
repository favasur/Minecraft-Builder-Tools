package net.buildertools.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * The local coordinate frame of a clicked block face, used to orient the Arch (ALT+A) and
 * Ellipse (ALT+E) mechanics: the face center is the Local Origin, the face normal is the Forward
 * vector, and {@code right}/{@code up} are perpendicular unit vectors spanning the face plane -
 * the 2D plane where the arch or elliptical cross-section is computed. Extruding along the
 * forward vector (the structure's depth) makes the shape branch off the face like a tunnel: a
 * wall (vertical) face gives an upright arch/ring, a floor/ceiling (horizontal) face gives a
 * sideways one, automatically, no matter which side is clicked.
 */
public record FaceFrame(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up) {

    /** Builds the frame of the given cell face: origin at the face center, forward = the face
     *  normal, right/up spanning the face plane (up = +Y for wall faces, horizontal for
     *  floor/ceiling faces). */
    public static FaceFrame of(BlockPos cell, Direction face) {
        Vec3 forward = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
        Vec3 up;
        Vec3 right;
        if (face.getAxis().isVertical()) {
            // Floor/ceiling face: the plane is horizontal (XZ).
            right = new Vec3(1, 0, 0);
            up = new Vec3(0, 0, 1);
        } else {
            // Wall face: up is +Y, right completes the frame.
            up = new Vec3(0, 1, 0);
            right = up.cross(forward);
        }
        Vec3 origin = Vec3.atCenterOf(cell).add(forward.scale(0.5));
        return new FaceFrame(origin, forward, right, up);
    }
}
