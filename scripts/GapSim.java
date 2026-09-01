import net.buildertools.util.BezierBlockData;
import net.buildertools.util.BezierGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Replicates the ring loop of BuilderServerHandler.archBlocksCore for the Bezier wall arch and
 * checks whether wedges from different rings key the same cell (the later RotationStore.set
 * overwrites the earlier wedge, which then vanishes -> 1x1/1x2 gaps in the arch).
 */
public class GapSim {

    static BlockPos floor(Vec3 p) {
        return new BlockPos((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
    }

    record Wedge(BezierBlockData data, BlockPos cell) {
    }

    static List<Wedge> generate(BezierGeometry.BezierArch bz, int wLayerMin, int wLayerMax,
                                int vColMin, int vColMax) {
        Vec3 v = bz.v();
        Vec3 rise = bz.w();
        List<Wedge> wedges = new ArrayList<>();
        Set<BlockPos> used = new HashSet<>();
        for (int wLayer = wLayerMin; wLayer <= wLayerMax; wLayer++) {
            for (int vCol = vColMin; vCol <= vColMax; vCol++) {
                List<Wedge> ring = new ArrayList<>();
                for (int i = 0; i < bz.count(); i++) {
                    Vec3 offset = v.scale(vCol).add(rise.scale(wLayer));
                    BezierBlockData data = BezierGeometry.blockData(bz, i, offset);
                    BlockPos cell = floor(BezierGeometry.wedgeCenter(data));
                    if (!ring.isEmpty()) {
                        Wedge last = ring.get(ring.size() - 1);
                        if (last.cell().equals(cell)) {
                            BezierBlockData d = last.data();
                            ring.set(ring.size() - 1, new Wedge(
                                    BezierGeometry.extend(d, data.t1() - d.t1()), cell));
                            continue;
                        }
                    }
                    cell = freeCellFor(data, cell, used);
                    used.add(cell);
                    ring.add(new Wedge(data, cell));
                }
                wedges.addAll(ring);
            }
        }
        return wedges;
    }

    /** Nearest free cell the wedge covers (corners first, then expanding cube), like
     *  BuilderServerHandler.freeCellFor. */
    static BlockPos freeCellFor(BezierBlockData data, BlockPos preferred, Set<BlockPos> used) {
        if (!used.contains(preferred)) return preferred;
        Set<BlockPos> candidates = new LinkedHashSet<>();
        for (Vec3 corner : BezierGeometry.wedgeVertices(data)) {
            candidates.add(floor(corner));
        }
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
                        candidates.add(preferred.offset(dx, dy, dz));
                    }
                }
            }
        }
        Vec3 c = BezierGeometry.wedgeCenter(data);
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos cand : candidates) {
            if (used.contains(cand)) continue;
            double dist = new Vec3(cand.getX() + 0.5, cand.getY() + 0.5, cand.getZ() + 0.5).distanceToSqr(c);
            if (dist < bestDist) { bestDist = dist; best = cand; }
        }
        return best != null ? best : preferred;
    }

    public static void main(String[] args) {
        // Wall scenario: pile 3 tall, extended 8 along +X; click to the right on +Z.
        // A = root of the wall (its first cell centre), C = handle (the wall's far end),
        // B = click destination (+Z, forward of the far end).
        Vec3 a = new Vec3(0.5, 1.0, 0.5);
        Vec3 c = new Vec3(7.5, 1.0, 0.5);
        for (Vec3 b : new Vec3[]{
                new Vec3(7.5, 1.0, 5.5),   // click straight right of the far end
                new Vec3(11.5, 1.0, 7.5),  // click right AND forward
                new Vec3(3.5, 1.0, 8.5),   // click right of the wall's middle
        }) {
            Vec3 v = new Vec3(0, 1, 0);         // depth axis: wall thickness
            Vec3 u = b.subtract(a).normalize();
            Vec3 w = v.cross(u);                // rise axis in the arch plane
            BezierGeometry.BezierArch bz = BezierGeometry.build(a, c, b, v, w);
            List<Wedge> wedges = generate(bz, -1, 1, 0, 1);
            Map<BlockPos, List<Integer>> owners = new HashMap<>();
            for (Wedge wg : wedges) {
                owners.computeIfAbsent(wg.cell(), k -> new ArrayList<>()).add(1);
            }
            int dups = 0;
            for (Map.Entry<BlockPos, List<Integer>> e : owners.entrySet()) {
                if (e.getValue().size() > 1) dups++;
            }
            System.out.printf("B=%s count=%d rings=3 wedges=%d uniqueCells=%d duplicateCells=%d (lost wedges=%d)%n",
                    b, bz.count(), wedges.size(), owners.size(), dups, wedges.size() - owners.size());
        }
    }
}