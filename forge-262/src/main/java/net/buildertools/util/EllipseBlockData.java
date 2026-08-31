package net.buildertools.util;

import com.mojang.serialization.Codec;

/**
 * The wedge geometry of ONE voussoir of a full elliptical ring (the ALT+E mechanic): a tapered
 * slice of a closed ellipse, wider outside and narrower inside (radial thickness 1m: 0.5m inside
 * the centerline, 0.5m outside), spanning an equal ~1m arc-length step along the centerline and
 * extruded 1m along the depth axis {@code v = u x w}.
 *
 * <p>The ellipse lives in the plane spanned by {@code u} (the semi-major axis direction) and
 * {@code w} (the semi-minor axis direction); {@code (cx, cy, cz)} is the ellipse CENTER, so the
 * centerline is {@code P(t) = C + u*a*cos(t) + w*b*sin(t)}. {@code a}/{@code b} are the
 * CENTERLINE semi-axes (the outer edge sits 0.5m further out). {@code thetaStart} is the angle of
 * the wedge's near edge and {@code deltaTheta} its angular width (radians); the wedge's radial
 * faces are at {@code thetaStart} and {@code thetaStart + deltaTheta}. The 8 corners are computed
 * by {@link EllipseGeometry#wedgeVertices(EllipseBlockData)}; every value is deterministic, so the
 * same wedge can be rendered, collided and raycast on any side.
 *
 * @param cx {@code cy} {@code cz} the ellipse center in world space
 * @param ux {@code uy} {@code uz} unit semi-major axis direction
 * @param wx {@code wy} {@code wz} unit semi-minor axis direction
 * @param a centerline semi-major axis (m)
 * @param b centerline semi-minor axis (m)
 * @param thetaStart start angle of this wedge along the ellipse (radians)
 * @param deltaTheta angular width of this wedge (radians)
 */
public record EllipseBlockData(double cx, double cy, double cz,
                               double ux, double uy, double uz,
                               double wx, double wy, double wz,
                               double a, double b,
                               double thetaStart, double deltaTheta) {

    /** The depth half-extent of every voussoir (1m total depth, like the original cube). */
    public static final double DEPTH_HALF = 0.5;

    /** Codec for the 26.2 Codec-based saved data (13 doubles in fixed order). */
    public static final Codec<EllipseBlockData> CODEC = Codec.DOUBLE.listOf().xmap(
            l -> new EllipseBlockData(
                    l.get(0), l.get(1), l.get(2),
                    l.get(3), l.get(4), l.get(5),
                    l.get(6), l.get(7), l.get(8),
                    l.get(9), l.get(10),
                    l.get(11), l.get(12)),
            e -> java.util.List.of(
                    e.cx(), e.cy(), e.cz(),
                    e.ux(), e.uy(), e.uz(),
                    e.wx(), e.wy(), e.wz(),
                    e.a(), e.b(),
                    e.thetaStart(), e.deltaTheta()));
}
