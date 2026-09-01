import net.buildertools.util.BezierBlockData;
import net.buildertools.util.BezierGeometry;
import net.buildertools.util.ArchGeometry;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Prints the UV coordinate ranges of every face of two consecutive bezier wedges, plus the
 * start/end faces, to verify the texture phase is continuous across wedge seams and tiled
 * once per meter (not stretched).
 */
public class UVDump {
    public static void main(String[] args) {
        // A realistic wall arch: root A at (0.5,1,0.5), handle C at (7.5,1,0.5), click B right
        // of the far end. The arch bends horizontally; v (depth) is vertical.
        Vec3 a = new Vec3(0.5, 1.0, 0.5);
        Vec3 c = new Vec3(7.5, 1.0, 0.5);
        Vec3 b = new Vec3(7.5, 1.0, 5.5);
        Vec3 v = new Vec3(0, 1, 0);
        Vec3 u = b.subtract(a).normalize();
        Vec3 w = v.cross(u);
        BezierGeometry.BezierArch arch = BezierGeometry.build(a, c, b, v, w);
        System.out.println("count=" + arch.count());
        String[] names = {"INNER", "OUTER", "START", "END", "BACK", "FRONT"};
        for (int i = 0; i < 2; i++) {
            BezierBlockData d = BezierGeometry.blockData(arch, i, Vec3.ZERO);
            System.out.printf("--- wedge %d: t0=%.3f t1=%.3f len=%.3fm%n", i, d.t0(), d.t1(),
                    BezierGeometry.wedgeVertices(d)[0].distanceTo(BezierGeometry.wedgeVertices(d)[2]));
            List<ArchGeometry.WedgeFace> faces = BezierGeometry.wedgeFaces(d);
            for (int k = 0; k < faces.size(); k++) {
                ArchGeometry.WedgeFace f = faces.get(k);
                double umin = 1e9, umax = -1e9, vmin = 1e9, vmax = -1e9;
                for (int j = 0; j < 4; j++) {
                    umin = Math.min(umin, f.u()[j]);
                    umax = Math.max(umax, f.u()[j]);
                    vmin = Math.min(vmin, f.v()[j]);
                    vmax = Math.max(vmax, f.v()[j]);
                }
                System.out.printf("  %-6s u=[%.3f..%.3f] (%+.3fm) v=[%.3f..%.3f] (%+.3fm)%n",
                        names[f.kind()], umin, umax, umax - umin, vmin, vmax, vmax - vmin);
            }
        }
        // Phase continuity at the seam: last u of wedge 0's LAST FRONT sub-quad vs the first u
        // of wedge 1's FIRST FRONT sub-quad (faces: 4 quads per sub-division in the order
        // INNER, OUTER, BACK, FRONT, then START + END; the last FRONT is index 4*(n-1)+3).
        BezierBlockData d0 = BezierGeometry.blockData(arch, 0, Vec3.ZERO);
        BezierBlockData d1 = BezierGeometry.blockData(arch, 1, Vec3.ZERO);
        List<ArchGeometry.WedgeFace> f0 = BezierGeometry.wedgeFaces(d0);
        List<ArchGeometry.WedgeFace> f1 = BezierGeometry.wedgeFaces(d1);
        int n = ArchGeometry.ARC_SUBDIVISIONS;
        double last0 = f0.get(4 * (n - 1) + 3).u()[2];
        double first1 = f1.get(3).u()[0];
        System.out.printf("seam: wedge0 LAST front u=%.3f, wedge1 FIRST front u=%.3f (gap %.3f)%n",
                last0, first1, first1 - last0);
    }
}