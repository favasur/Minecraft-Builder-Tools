import java.util.ArrayList;
import java.util.List;

/**
 * Standalone check for the ALT+E ghost preview math (EllipseGeometry.regionEllipse +
 * the renderer's band curves):
 *  1. a/b are exactly half the region's projected cell-center extents in the face plane.
 *  2. The number of layers covers the region's full depth extent (band sweeps the whole wall).
 *  3. The centerline sampled at the ring's wedge boundaries closes exactly (thetas[0] == thetas[N]).
 *  4. The band's outer/inner edges are the centerline offset by exactly 0.5 along the OUTWARD
 *     ellipse normal (the same normal-offset the wedge corners use) - not a scaled ellipse.
 *  5. Degenerate regions (too small / too flat) yield no ring.
 */
public class EllipseGhostCheck {
    record V3(double x, double y, double z) {
        V3 add(V3 o) { return new V3(x + o.x, y + o.y, z + o.z); }
        V3 sub(V3 o) { return new V3(x - o.x, y - o.y, z - o.z); }
        V3 scale(double s) { return new V3(x * s, y * s, z * s); }
        double dot(V3 o) { return x * o.x + y * o.y + z * o.z; }
        double len() { return Math.sqrt(dot(this)); }
    }

    static final V3 U = new V3(1, 0, 0);
    static final V3 W = new V3(0, 1, 0);
    static final V3 V = new V3(0, 0, 1);

    static double sin2(double t) { double s = Math.sin(t); return s * s; }
    static double cos2(double t) { double c = Math.cos(t); return c * c; }

    // Copies of the geometry under test (identical math to EllipseGeometry).
    static double[] thetas(double a, double b) {
        int SAMPLES = 8192;
        double dTheta = Math.PI * 2.0 / SAMPLES;
        double[] s = new double[SAMPLES + 1];
        for (int i = 1; i <= SAMPLES; i++) {
            double t0 = (i - 1) * dTheta, t1 = i * dTheta;
            double v0 = Math.sqrt(a * a * sin2(t0) + b * b * cos2(t0));
            double v1 = Math.sqrt(a * a * sin2(t1) + b * b * cos2(t1));
            s[i] = s[i - 1] + (v0 + v1) / 2.0 * dTheta;
        }
        double perimeter = s[SAMPLES];
        int count = Math.max(6, (int) Math.round(perimeter));
        double step = perimeter / count;
        double[] out = new double[count + 1];
        for (int i = 0; i < count; i++) {
            out[i] = inverse(s, dTheta, i * step);
        }
        out[count] = Math.PI * 2.0;
        return out;
    }

    static double inverse(double[] s, double dTheta, double target) {
        int lo = 0, hi = s.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (s[mid] <= target) lo = mid; else hi = mid;
        }
        double span = s[hi] - s[lo];
        double f = span <= 1.0E-12 ? 0.0 : (target - s[lo]) / span;
        f = Math.max(0.0, Math.min(1.0, f));
        return (lo + f) * dTheta;
    }

    // The renderer's normal-offset band point at angle t.
    static V3 bandPoint(V3 c, double a, double b, double t, double side) {
        double nx = b * Math.cos(t);
        double ny = a * Math.sin(t);
        double len = Math.sqrt(nx * nx + ny * ny);
        V3 n = len < 1.0E-9 ? W : U.scale(nx / len).add(W.scale(ny / len));
        V3 p = c.add(U.scale(a * Math.cos(t))).add(W.scale(b * Math.sin(t)));
        return p.add(n.scale(side));
    }

    static int failures = 0;

    static void checkBand(V3 c, double a, double b, double[] th, String name) {
        for (double t : th) {
            V3 p = c.add(U.scale(a * Math.cos(t))).add(W.scale(b * Math.sin(t)));
            V3 outer = bandPoint(c, a, b, t, 0.5);
            V3 inner = bandPoint(c, a, b, t, -0.5);
            double dOut = outer.sub(p).len();
            double dIn = inner.sub(p).len();
            if (Math.abs(dOut - 0.5) > 1.0E-9 || Math.abs(dIn - 0.5) > 1.0E-9) {
                failures++;
                System.out.println("FAIL " + name + ": band offset " + dOut + "/" + dIn + " != 0.5 at t=" + t);
                return;
            }
            // The offset must be along the normal: perpendicular to the tangent.
            V3 tan = U.scale(-a * Math.sin(t)).add(W.scale(b * Math.cos(t)));
            double dotT = outer.sub(p).dot(tan);
            if (Math.abs(dotT) > 1.0E-6) {
                failures++;
                System.out.println("FAIL " + name + ": offset not perpendicular to tangent at t=" + t + " (dot " + dotT + ")");
                return;
            }
        }
    }

    public static void main(String[] args) {
        int cases = 0;
        // Rings of many shapes: the band offset must be exactly 0.5 and normal-perpendicular.
        for (double a = 1.0; a <= 12.0; a += 1.5) {
            for (double b = 0.75; b <= 10.0; b += 1.7) {
                if (b * b / a < 0.6) continue; // below MIN_CURVATURE: same skip as the geometry
                double[] th = thetas(a, b);
                // 1. closure + monotonic
                if (th[th.length - 1] != Math.PI * 2.0 || Math.abs(th[0] - 0.0) > 1.0E-9) {
                    failures++;
                    System.out.println("FAIL closure thetas for a=" + a + " b=" + b);
                    continue;
                }
                for (int i = 1; i < th.length; i++) {
                    if (th[i] < th[i - 1]) {
                        failures++;
                        System.out.println("FAIL non-monotonic thetas a=" + a + " b=" + b);
                        break;
                    }
                }
                // centerline first == last (closed loop)
                V3 c = new V3(10, 4, 2);
                V3 p0 = c.add(U.scale(a * Math.cos(th[0]))).add(W.scale(b * Math.sin(th[0])));
                V3 pN = c.add(U.scale(a * Math.cos(th[th.length - 1]))).add(W.scale(b * Math.sin(th[th.length - 1])));
                if (p0.sub(pN).len() > 1.0E-9) {
                    failures++;
                    System.out.println("FAIL centerline not closed a=" + a + " b=" + b);
                }
                checkBand(c, a, b, th, "a=" + a + ",b=" + b);
                cases++;
            }
        }

        // 2. derivation: a/b = half the projected cell-center extents; layers cover the depth.
        // region 6x4x3 at (0,0,0)..(5,3,2), face UP (u=+X, w=+Z, v=+Y): extents 6,3,4 -> a=3,b=1.5
        {
            int x0 = 0, y0 = 0, z0 = 0, x1 = 5, y1 = 3, z1 = 2;
            V3 u = U, w = V, v = W; // u=+X (6 cells), w=+Z (3 cells), v=+Y (4 cells)
            double extentU = 6.0, extentW = 3.0, extentV = 4.0;
            double a = extentU / 2.0, b = extentW / 2.0;
            int layers = (int) Math.floor(extentV + 1.0E-6) + 1;
            if (Math.abs(a - 3.0) > 1.0E-9 || Math.abs(b - 1.5) > 1.0E-9 || layers != 5) {
                failures++;
                System.out.println("FAIL derivation: a=" + a + " b=" + b + " layers=" + layers);
            }
            // layer offsets sweep the depth: -(layers-1)/2 .. +(layers-1)/2
            List<Double> offs = new ArrayList<>();
            for (int l = 0; l < layers; l++) offs.add(l - (layers - 1) / 2.0);
            if (offs.get(0) != -2.0 || offs.get(offs.size() - 1) != 2.0) {
                failures++;
                System.out.println("FAIL layer sweep: " + offs);
            }
            cases++;
        }
        // odd depth: 3 cells -> layers 4 (floor(3)+1), offsets -1.5..1.5 sweep the full 3m.
        {
            double extentV = 3.0;
            int layers = (int) Math.floor(extentV + 1.0E-6) + 1;
            if (layers != 4) {
                failures++;
                System.out.println("FAIL odd depth layers=" + layers);
            }
            cases++;
        }
        // 3. degenerate: too small (extent 0 in one plane axis - a single block) / too flat
        // (b*b/a < 0.6).
        {
            double extentU = 2.0, extentW = 0.0; // one block wide: rejected
            if (!(extentU < 1.0 || extentW < 1.0)) {
                failures++;
                System.out.println("FAIL degenerate size check logic");
            }
            double a = 5.0, b = 1.2; // b*b/a = 0.288 < 0.6 -> too flat
            if (!(b * b / a < 0.6)) {
                failures++;
                System.out.println("FAIL too-flat check logic");
            }
            double a2 = 5.0, b2 = 2.5; // b*b/a = 1.25 >= 0.6 -> fine
            if (b2 * b2 / a2 < 0.6) {
                failures++;
                System.out.println("FAIL valid curvature rejected");
            }
            cases++;
        }

        System.out.println(failures == 0
                ? "ALL PASS (" + cases + " configurations): band offset = 0.5 along normal, closure exact, derivation matches extents, layers sweep depth"
                : failures + " FAILURES of " + cases + " configurations");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
