import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Standalone check for the arch ghost preview's multi-column band:
 * the corner-based archColumn range (min/max of round((cellCenter - boxCenter).v) over the 8
 * corners) must equal the server's per-cell distinct column set (round of the same dot for every
 * cell in the region). Also verifies a 1-wide wall yields a single column at index 0 (the
 * pre-existing preview behavior is unchanged).
 */
public class ArchColumnCheck {
    record V3(double x, double y, double z) {
        double dot(V3 o) {
            return x * o.x + y * o.y + z * o.z;
        }

        V3 sub(V3 o) {
            return new V3(x - o.x, y - o.y, z - o.z);
        }
    }

    static V3 atCenterOf(int x, int y, int z) {
        return new V3(x + 0.5, y + 0.5, z + 0.5);
    }

    static V3 boxCenter(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new V3(x0 + (x1 - x0 + 1) / 2.0, y0 + (y1 - y0 + 1) / 2.0, z0 + (z1 - z0 + 1) / 2.0);
    }

    // The corner-based version (as added to SelectionRenderer.archColumn).
    static int[] cornerRange(int x0, int y0, int z0, int x1, int y1, int z1, V3 center, V3 v) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    int cx = x0 + dx * (x1 - x0), cy = y0 + dy * (y1 - y0), cz = z0 + dz * (z1 - z0);
                    double d = atCenterOf(cx, cy, cz).sub(center).dot(v);
                    lo = Math.min(lo, d);
                    hi = Math.max(hi, d);
                }
            }
        }
        return new int[]{(int) Math.round(lo), (int) Math.round(hi)};
    }

    // The server's per-cell distinct columns.
    static Set<Integer> perCell(int x0, int y0, int z0, int x1, int y1, int z1, V3 center, V3 v) {
        Set<Integer> cols = new TreeSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    cols.add((int) Math.round(atCenterOf(x, y, z).sub(center).dot(v)));
                }
            }
        }
        return cols;
    }

    static int failures = 0;

    static void check(int x0, int y0, int z0, int x1, int y1, int z1, V3 v) {
        V3 center = boxCenter(x0, y0, z0, x1, y1, z1);
        int[] range = cornerRange(x0, y0, z0, x1, y1, z1, center, v);
        Set<Integer> cells = perCell(x0, y0, z0, x1, y1, z1, center, v);
        int expectedLo = cells.iterator().next();
        int expectedHi = cells.stream().mapToInt(Integer::intValue).max().getAsInt();
        boolean contiguous = cells.size() == expectedHi - expectedLo + 1;
        boolean ok = range[0] == expectedLo && range[1] == expectedHi && contiguous
                && cells.stream().allMatch(c -> c >= range[0] && c <= range[1]);
        if (!ok) {
            failures++;
            System.out.println("FAIL region (" + x0 + "," + y0 + "," + z0 + ")-(" + x1 + "," + y1 + "," + z1
                    + ") v=(" + v.x + "," + v.y + "," + v.z + "): corner " + range[0] + ".." + range[1]
                    + " vs per-cell " + cells + (contiguous ? "" : " (NOT contiguous)"));
        }
    }

    public static void main(String[] args) {
        V3 PX = new V3(1, 0, 0), NX = new V3(-1, 0, 0);
        V3 PY = new V3(0, 1, 0), NY = new V3(0, -1, 0);
        V3 PZ = new V3(0, 0, 1), NZ = new V3(0, 0, -1);
        List<V3> axes = List.of(PX, NX, PY, NY, PZ, NZ);

        int cases = 0;
        // 1-wide walls (depth 1 along every axis) - must be a single column at 0.
        for (V3 v : axes) {
            for (int sx = 1; sx <= 9; sx++) {
                for (int sy = 1; sy <= 9; sy++) {
                    for (int sz = 1; sz <= 9; sz++) {
                        // only boxes with at least one dimension 1 (a "wall" shape)
                        if (sx != 1 && sy != 1 && sz != 1) {
                            continue;
                        }
                        int ox = 7, oy = -3, oz = 11;
                        check(ox, oy, oz, ox + sx - 1, oy + sy - 1, oz + sz - 1, v);
                        cases++;
                    }
                }
            }
        }
        // Multi-wide slabs (depth 2-6) at various offsets and orientations.
        for (V3 v : axes) {
            for (int depth = 2; depth <= 6; depth++) {
                for (int len = 3; len <= 12; len += 3) {
                    for (int h = 1; h <= 4; h++) {
                        // depth along X, length along Z, height along Y (generic box)
                        check(0, 0, 0, depth - 1, h - 1, len - 1, v);
                        check(5, -4, 3, 5 + depth - 1, -4 + h - 1, 3 + len - 1, v);
                        cases += 2;
                    }
                }
            }
        }
        // The canonical multi-wide-wall shape: span 8, height 3, depth 3/4/5.
        for (int d = 3; d <= 5; d++) {
            check(0, 0, 0, 2, 2, 7, PY);
            check(0, 0, 0, 2, 2, 7, PZ);
            check(0, 0, 0, 2, 2, 7, NX);
            cases += 3;
        }

        System.out.println(failures == 0
                ? "ALL PASS (" + cases + " configurations): corner range == per-cell columns, contiguous, 1-wide single column at 0"
                : failures + " FAILURES of " + cases + " configurations");
        if (failures > 0) {
            System.exit(1);
        }
    }
}
