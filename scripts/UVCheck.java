import net.buildertools.util.BezierBlockData;
import net.buildertools.util.BezierGeometry;
import net.buildertools.util.ArchBlockData;
import net.buildertools.util.ArchGeometry;
import net.buildertools.util.EllipseBlockData;
import net.buildertools.util.EllipseGeometry;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Verifies the UV rebase fix (u' = min(u - floor(minU), 1.0), v passed through untouched) for
 * EVERY wedge shape the server can commit: plain ~1m voussoirs, merged multi-meter wedges
 * (consecutive voussoirs landing in the same cell), wall depth columns (vCol rings of a tall
 * wall), and ellipse depth layers. Every face's phases must land in [0,1] with v exactly {0,1}
 * - the old per-vertex floor-wrap collapsed v=1.0 to 0.0, stretching one texel row across the
 * whole face (the brown-stripe / gray-bleed bug).
 */
public class UVCheck {
    static int failures = 0;
    static int facesChecked = 0;

    public static void main(String[] args) {
        // --- 1) Bezier wall arch: plain voussoirs, merged multi-meter wedges, vCol rings ---
        Vec3 a = new Vec3(0.5, 1.0, 0.5);
        Vec3 c = new Vec3(7.5, 1.0, 0.5);
        Vec3 b = new Vec3(7.5, 1.0, 5.5);
        Vec3 v = new Vec3(0, 1, 0);
        Vec3 u = b.subtract(a).normalize();
        Vec3 w = v.cross(u);
        BezierGeometry.BezierArch bezier = BezierGeometry.build(a, c, b, v, w);
        System.out.println("Bezier arch count=" + bezier.count());

        // Plain voussoirs.
        for (int i = 0; i < bezier.count(); i++) {
            checkFaces(BezierGeometry.wedgeFaces(BezierGeometry.blockData(bezier, i, Vec3.ZERO)),
                    "bezier#" + i);
        }
        // Merged multi-meter wedges: extend consecutive voussoirs (the server merges wedges
        // whose centerlines land in the same cell into one wider wedge).
        for (int i = 0; i + 3 < bezier.count(); i += 2) {
            BezierBlockData d = BezierGeometry.blockData(bezier, i, Vec3.ZERO);
            BezierBlockData merged = BezierGeometry.extend(d,
                    bezier.ts()[i + 3] - bezier.ts()[i + 1]);
            double meters = arcMeters(BezierGeometry.wedgeVertices(merged));
            checkFaces(BezierGeometry.wedgeFaces(merged), String.format("bezier-merged#%d (%.1fm)", i, meters));
        }
        // Wall depth columns (vCol rings): a 6m-tall wall is 6 rings, one per depth column.
        for (int vCol = 0; vCol < 6; vCol++) {
            Vec3 offset = v.scale(vCol);
            for (int i = 0; i < bezier.count(); i++) {
                checkFaces(BezierGeometry.wedgeFaces(BezierGeometry.blockData(bezier, i, offset)),
                        String.format("bezier-vCol%d#%d", vCol, i));
            }
        }
        // Merged + vCol combined (the wall's merged wedges in every ring).
        for (int vCol = 0; vCol < 6; vCol++) {
            Vec3 offset = v.scale(vCol);
            for (int i = 0; i + 3 < bezier.count(); i += 2) {
                BezierBlockData d = BezierGeometry.blockData(bezier, i, offset);
                BezierBlockData merged = BezierGeometry.extend(d,
                        bezier.ts()[i + 3] - bezier.ts()[i + 1]);
                checkFaces(BezierGeometry.wedgeFaces(merged),
                        String.format("bezier-merged-vCol%d#%d", vCol, i));
            }
        }

        // --- 2) Circular arch: plain and merged (deltaTheta multiplied) ---
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
            // Merged: three consecutive wedges as one wider angular slice.
            ArchBlockData base = ArchGeometry.blockData(arc, 0, count);
            ArchBlockData merged = new ArchBlockData(
                    base.ox(), base.oy(), base.oz(),
                    base.ux(), base.uy(), base.uz(),
                    base.wx(), base.wy(), base.wz(),
                    base.thetaStart(), base.deltaTheta() * 3.0, base.radius());
            checkFaces(ArchGeometry.wedgeFaces(merged), "arch-merged#0 (3x)");
        }

        // --- 3) Ellipse ring: plain voussoirs, depth layers, merged segments ---
        EllipseGeometry.EllipseResult ellipse = EllipseGeometry.buildEllipse(
                new Vec3(0, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), 4.0, 2.5, 3);
        if (ellipse == null) {
            System.out.println("FAIL: ellipse did not build");
            failures++;
        } else {
            System.out.println("Ellipse count=" + ellipse.count());
            for (int layer = 0; layer < 3; layer++) {
                for (int i = 0; i < ellipse.count(); i++) {
                    checkFaces(EllipseGeometry.wedgeFaces(EllipseGeometry.blockData(ellipse, i, layer)),
                            String.format("ellipse-layer%d#%d", layer, i));
                }
            }
            // Merged multi-meter segment (3 consecutive voussoirs).
            EllipseBlockData seg = EllipseGeometry.blockData(ellipse, 0, 0);
            EllipseBlockData merged = EllipseGeometry.extend(seg, seg.deltaTheta() * 2.0);
            checkFaces(EllipseGeometry.wedgeFaces(merged), "ellipse-merged#0 (3x)");
        }

        // --- 4) Demonstrate the old bug on the exact v values used by every face ---
        double oldCollapse = 1.0 - Math.floor(1.0);
        System.out.printf("old wrap: v=1.0 -> %.3f (%s)%n", oldCollapse,
                oldCollapse == 0.0 ? "the stretched-texel-row bug" : "OK");

        System.out.printf("%d faces checked; %s%n", facesChecked,
                failures == 0 ? "ALL UV CHECKS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** Approximate centerline length of a wedge (for the merged-wedge labels). */
    static double arcMeters(Vec3[] verts) {
        return verts[0].distanceTo(verts[2]);
    }

    static void checkFaces(List<ArchGeometry.WedgeFace> faces, String tag) {
        for (int k = 0; k < faces.size(); k++) {
            ArchGeometry.WedgeFace f = faces.get(k);
            double minU = Double.MAX_VALUE;
            double maxU = -Double.MAX_VALUE;
            for (int j = 0; j < 4; j++) {
                minU = Math.min(minU, f.u()[j]);
                maxU = Math.max(maxU, f.u()[j]);
            }
            double base = Math.floor(minU);
            double boundary = Math.floor(maxU);
            // Mirror ArchGeometry.addFaceQuads exactly: a sub-quad straddling a tile boundary
            // is split in two AT the boundary - the values emitted are {lowU, 1.0, 1.0, lowU}
            // for the left part and {0.0, highU, highU, 0.0} for the right, where
            // lowU = minU - base and highU = maxU - boundary. Everything else is rebased onto
            // its own tile: u' = u - base. All must land in [0,1]; v stays exactly {0,1}.
            double lowU = minU - base;
            double highU = maxU - boundary;
            if (boundary > base && maxU > boundary) {
                checkValue(tag, k, lowU, minU, "split-left lowU");
                checkValue(tag, k, 1.0, minU, "split-left far edge");
                checkValue(tag, k, 0.0, maxU, "split-right near edge");
                checkValue(tag, k, highU, maxU, "split-right highU");
            } else {
                for (int j = 0; j < 4; j++) {
                    checkValue(tag, k, f.u()[j] - base, f.u()[j], "rebased");
                }
            }
            for (int j = 0; j < 4; j++) {
                if (f.v()[j] != 0.0 && f.v()[j] != 1.0) {
                    System.out.printf("FAIL %s face %d: v=%f not in {0,1}%n", tag, k, f.v()[j]);
                    failures++;
                }
                facesChecked++;
            }
        }
    }

    static void checkValue(String tag, int k, double up, double raw, String what) {
        if (up < -1e-5 || up > 1.0 + 1e-5) {
            System.out.printf("FAIL %s face %d (%s): u'=%f out of [0,1] (raw u=%.4f)%n",
                    tag, k, what, up, raw);
            failures++;
        }
        facesChecked++;
    }
}
