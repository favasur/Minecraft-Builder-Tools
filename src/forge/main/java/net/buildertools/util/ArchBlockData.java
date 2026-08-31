package net.buildertools.util;

/**
 * The wedge geometry of ONE arch voussoir (an arched replacement for a row block). A voussoir is
 * a tapered slice of a circular arch: its side profile is bounded by two concentric arcs (the
 * inner radius {@code radius - 0.5} and the outer radius {@code radius + 0.5}, so the radial
 * thickness stays exactly 1m) spanning {@code deltaTheta} radians, extruded 1m along the depth
 * axis {@code v = u x w}.
 *
 * <p>The arch lives in the plane spanned by {@code u} (the span direction, from the first to the
 * last row block) and {@code w} (the rise direction, toward the clicked block). All vectors are
 * world-space; {@code (ox, oy, oz)} is the circle center the arc belongs to. {@code thetaStart}
 * is the angle of the wedge's near edge, {@code deltaTheta} the angular width (radians), and
 * {@code radius} the CENTERLINE radius {@code R} of the arch. The 8 corners are computed by
 * {@link ArchGeometry#wedgeVertices(ArchBlockData)}; every value here is deterministic, so the
 * same wedge can be rendered, collided and raycast on any side (client or dedicated server).
 *
 * @param ox {@code oy} {@code oz} the arch circle center in world space
 * @param ux {@code uy} {@code uz} unit span direction (first row block -> last row block)
 * @param wx {@code wy} {@code wz} unit rise direction (chord midpoint -> clicked block)
 * @param thetaStart start angle of this wedge along the arc (radians)
 * @param deltaTheta angular width of this wedge (radians)
 * @param radius centerline radius R of the arch (m)
 */
public record ArchBlockData(double ox, double oy, double oz,
                            double ux, double uy, double uz,
                            double wx, double wy, double wz,
                            double thetaStart, double deltaTheta, double radius) {

    /** The depth half-extent of every voussoir (1m total depth, like the original cube). */
    public static final double DEPTH_HALF = 0.5;

    public double innerRadius() {
        return radius - 0.5;
    }

    public double outerRadius() {
        return radius + 0.5;
    }
}
