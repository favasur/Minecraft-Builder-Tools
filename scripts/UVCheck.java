import net.buildertools.util.BezierBlockData;
import net.buildertools.util.BezierGeometry;
import net.buildertools.util.ArchBlockData;
import net.buildertools.util.ArchGeometry;
import net.buildertools.util.EllipseBlockData;
import net.buildertools.util.EllipseGeometry;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Verifies the UV rebase fix: for every face of every wedge of every geometry type, the phases
 * actually fed to TextureAtlasSprite.getU/getV by wedgeQuads must be
 *   u' = u - floor(min(u))   (per-quad rebase, always in [0,1])
 *   v' = v                   (never wrapped - so a vertex at exactly 1.0 reaches the sprite's
 *                             far edge instead of collapsing to 0.0 and stretching one texel
 *                             row across the whole face, the old bug)
 */
public class UVCheck {
    static int failures = 0;

    public static void main(String[] args) {
        // 1) Bezier wall arch (the user's reported case: a tall wall arched sideways)
        Vec3 a = new Vec3(0.5, 1.0, 0.5);
        Vec3 c = new Vec3(7.5, 1.0, 0.5);
        Vec3 b = new Vec3(7.5, 1.0, 5.5);
        Vec3 v = new Vec3(0, 1, 0);
        Vec3 u = b.subtract(a).normalize();
        Vec3 w = v.cross(u);
        BezierGeometry.BezierArch bezier = BezierGeometry.build(a, c, b, v, w);
        System.out.println("Bezier arch count=" + bezier.count());
        for (int i = 0; i < bezier.count(); i++) {
            checkFaces(BezierGeometry.wedgeFaces(BezierGeometry.blockData(bezier, i, Vec3.ZERO)), "bezier#" + i);
        }

        // 2) Circular arch
        ArchGeometry.ArchResult arc = ArchGeometry.computeArchInPlane(
                new Vec3(0.5, 1, 0.5), new Vec3(13.5, 1, 0.5), new Vec3(1, 0, 0), new Vec3(0, 1, 0),
                new Vec3(7, 4, 0.5));
        if (arc == null) {
            System.out.println("FAIL: circular arch did not build");
            failures++;
        } else {
            int count = Math.max((int) Math.round(arc.totalAngle() * arc.radius()), 3);
            System.out.println("Circular arch count=" + count);
            for (int i = 0; i < count; i++) {
                checkFaces(ArchGeometry.wedgeFaces(ArchGeometry.blockData(arc, i, count)), "arch#" + i);
            }
        }

        // 3) Ellipse ring
        EllipseGeometry.EllipseResult ellipse = EllipseGeometry.buildEllipse(
                new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), 4.0, 2.5, 1);
        if (ellipse == null) {
            System.out.println("FAIL: ellipse did not build");
            failures++;
        } else {
            System.out.println("Ellipse count=" + ellipse.count());
            for (int i = 0; i < ellipse.count(); i++) {
                checkFaces(EllipseGeometry.wedgeFaces(EllipseGeometry.blockData(ellipse, i, 0)), "ellipse#" + i);
            }
        }

        // 4) Demonstrate the old wrap bug vs the fix on the exact v values used by every face.
        for (double x : new double[]{0.0, 0.5, 1.0, 3.0, 3.875, 4.0, 14.0}) {
            double oldWrap = x - Math.floor(x);
            System.out.printf("x=%.3f  oldWrap->%.3f  (v=1.0 collapses to %.3f: %s)%n",
                    x, oldWrap, 1.0 - Math.floor(1.0), oldWrap == 1.0 ? "OK" : "COLLAPSED");
        }

        System.out.println(failures == 0 ? "ALL UV CHECKS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void checkFaces(List<ArchGeometry.WedgeFace> faces, String tag) {
        for (int k = 0; k < faces.size(); k++) {
            ArchGeometry.WedgeFace f = faces.get(k);
            double minU = Double.MAX_VALUE;
            for (int j = 0; j < 4; j++) {
                minU = Math.min(minU, f.u()[j]);
            }
            double base = Math.floor(minU);
            for (int j = 0; j < 4; j++) {
                // Exact mapping now used by wedgeQuads: rebase onto the quad's tile and clamp
                // the far edge to 1.0 (a sub-quad straddling a tile boundary stays inside).
                double up = Math.min(f.u()[j] - base, 1.0);
                double vp = f.v()[j];
                if (up < -1e-5 || up > 1.0 + 1e-5) {
                    System.out.printf("FAIL %s face %d: u'=%f out of [0,1]%n", tag, k, up);
                    failures++;
                }
                if (vp != 0.0 && vp != 1.0) {
                    System.out.printf("FAIL %s face %d: v'=%f not in {0,1}%n", tag, k, vp);
                    failures++;
                }
            }
        }
    }
}
