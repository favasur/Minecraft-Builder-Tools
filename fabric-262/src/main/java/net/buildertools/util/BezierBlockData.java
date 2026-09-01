package net.buildertools.util;

import com.mojang.serialization.Codec;

/**
 * The wedge geometry of ONE voussoir of a Bezier arch (the ALT+A mechanic): a curved slice of a
 * quadratic Bezier band. The arch is {@code P(t) = (1-t)^2 * A + 2(1-t)t * C + t^2 * B} where
 * {@code A} is the wall's root node (the beginning of the wall), {@code C} is the control handle
 * (the extended wall itself, which disappears into the curve) and {@code B} is the final click
 * destination - so the curve leaves A along the wall, is pulled toward C, and always terminates
 * EXACTLY at B (the arch's length is free, never capped by the wall's span).
 *
 * <p>Each voussoir spans the parameter range {@code [t0, t1]} (about 1m of centerline arc length,
 * sampled by arc length) with radial thickness 1m - 0.5m inside and 0.5m outside the centerline
 * along the in-plane normal (perpendicular to the tangent) - extruded 1m along the depth axis
 * {@code v} (the normal of the A/C/B plane). The 8 corners are computed by
 * {@link BezierGeometry#wedgeVertices(BezierBlockData)}; every value is deterministic, so the same
 * wedge can be rendered, collided and raycast on any side.
 *
 * @param ax {@code ay} {@code az} node A - the wall's root (beginning of the wall)
 * @param cx {@code cy} {@code cz} control point C - the extended wall (the handle)
 * @param bx {@code by} {@code bz} node B - the final click destination
 * @param vx {@code vy} {@code vz} unit depth axis (normal of the arch plane)
 * @param t0 start of this wedge along the curve (0..1)
 * @param t1 end of this wedge along the curve (0..1)
 */
public record BezierBlockData(double ax, double ay, double az,
                              double cx, double cy, double cz,
                              double bx, double by, double bz,
                              double vx, double vy, double vz,
                              double t0, double t1) {

    /** The depth half-extent of every voussoir (1m total depth, like the original cube). */
    public static final double DEPTH_HALF = 0.5;

    /** Codec for the 26.2 Codec-based saved data (14 doubles in fixed order). */
    public static final Codec<BezierBlockData> CODEC = Codec.DOUBLE.listOf().xmap(
            l -> new BezierBlockData(
                    l.get(0), l.get(1), l.get(2),
                    l.get(3), l.get(4), l.get(5),
                    l.get(6), l.get(7), l.get(8),
                    l.get(9), l.get(10), l.get(11),
                    l.get(12), l.get(13)),
            e -> java.util.List.of(
                    e.ax(), e.ay(), e.az(),
                    e.cx(), e.cy(), e.cz(),
                    e.bx(), e.by(), e.bz(),
                    e.vx(), e.vy(), e.vz(),
                    e.t0(), e.t1()));
}
