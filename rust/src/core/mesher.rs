//! Smooth meshed terrain.
//!
//! Two different things in the Java mod carry the name, and both are here:
//!
//! * **The smoothing tool** (`SmoothToolItem`): averages a column's surface height towards its
//! neighbours inside a disc, then fills or cuts the difference. It is an ordinary, undoable block
//! edit, so it needs nothing from the API beyond what every other tool uses.
//! * **The meshed terrain** (the bundled `io.github.favasur.smoothterrain`): terrain drawn as a
//! surface through a density field rather than as cubes. The mesher below is the surface-nets part
//! of it — the part that is geometry — producing quads a section's mesh can hold. The Java mod's
//! chunk-rebuild mixins, its lighting and its mesh collision shape have no counterpart in API 0.2,
//! which is why this module stops at the mesh and says so in the README.
//!
//! Surface nets places one vertex per cell whose eight corners straddle the surface, at the average
//! of the cell's edge crossings, then joins the four cells around every sign-changing lattice edge
//! into a quad. That gives a closed, watertight surface with one vertex per surface cell — far
//! fewer than marching cubes — and quads instead of triangles, which is what a section mesh wants.

use super::ops::{BlockChange, MAX_BLOCKS};
use super::pos::{BlockBox, BlockPos};
use super::world::{BlockId, World};

/// A density field over one section, sampled on the corners of `n` cells per axis.
///
/// `n + 1` samples per axis: the cell at `(x, y, z)` reads the corners `(x..=x+1, y..=y+1, z..=z+1)`.
/// Positive is solid, negative is air, so a point exactly on the surface reads zero.
#[derive(Clone, Debug, PartialEq)]
pub struct DensityGrid {
    n: usize,
    values: Vec<f32>,
}

impl DensityGrid {
    /// A zeroed field: `n` cells per axis, every corner exactly on the surface.
    pub fn new(n: usize) -> Self {
        let side = n + 1;
        Self {
            n,
            values: vec![0.0; side * side * side],
        }
    }

    /// A field from a function of the corner coordinates.
    pub fn from_fn(n: usize, density: impl Fn(i32, i32, i32) -> f32) -> Self {
        let mut grid = Self::new(n);
        for y in 0..=n as i32 {
            for z in 0..=n as i32 {
                for x in 0..=n as i32 {
                    grid.set(x, y, z, density(x, y, z));
                }
            }
        }
        grid
    }

    pub fn cells(&self) -> usize {
        self.n
    }

    fn index(&self, x: i32, y: i32, z: i32) -> usize {
        let side = self.n + 1;
        (y as usize * side + z as usize) * side + x as usize
    }

    pub fn get(&self, x: i32, y: i32, z: i32) -> f32 {
        let n = self.n as i32;
        if x < 0 || y < 0 || z < 0 || x > n || y > n || z > n {
            return 0.0;
        }
        self.values[self.index(x, y, z)]
    }

    pub fn set(&mut self, x: i32, y: i32, z: i32, value: f32) {
        let n = self.n as i32;
        if x < 0 || y < 0 || z < 0 || x > n || y > n || z > n {
            return;
        }
        let index = self.index(x, y, z);
        self.values[index] = value;
    }
}

/// One quad of a section's smooth mesh, in blocks from the section's corner.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MeshQuad {
    pub corners: [[f64; 3]; 4],
    /// The outward normal, so a renderer can shade it without inferring the winding.
    pub normal: [f64; 3],
}

/// A section's smooth mesh.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct SectionMesh {
    pub quads: Vec<MeshQuad>,
    /// The vertices the quads were built from, one per surface cell.
    pub vertices: Vec<[f64; 3]>,
}

impl SectionMesh {
    pub fn is_empty(&self) -> bool {
        self.quads.is_empty()
    }

    /// The box the mesh occupies, or `None` for an empty mesh.
    pub fn bounds(&self) -> Option<([f64; 3], [f64; 3])> {
        let mut min = [f64::INFINITY; 3];
        let mut max = [f64::NEG_INFINITY; 3];
        for vertex in &self.vertices {
            for axis in 0..3 {
                min[axis] = min[axis].min(vertex[axis]);
                max[axis] = max[axis].max(vertex[axis]);
            }
        }
        min[0].is_finite().then_some((min, max))
    }
}

/// The 12 edges of a cell, as pairs of corner offsets.
const EDGES: [([i32; 3], [i32; 3]); 12] = [
    ([0, 0, 0], [1, 0, 0]),
    ([0, 0, 1], [1, 0, 1]),
    ([0, 1, 0], [1, 1, 0]),
    ([0, 1, 1], [1, 1, 1]),
    ([0, 0, 0], [0, 1, 0]),
    ([1, 0, 0], [1, 1, 0]),
    ([0, 0, 1], [0, 1, 1]),
    ([1, 0, 1], [1, 1, 1]),
    ([0, 0, 0], [0, 0, 1]),
    ([1, 0, 0], [1, 0, 1]),
    ([0, 1, 0], [0, 1, 1]),
    ([1, 1, 0], [1, 1, 1]),
];

/// The surface through `grid`, as quads.
pub fn surface_nets(grid: &DensityGrid) -> SectionMesh {
    let n = grid.cells() as i32;
    // One vertex per cell that straddles the surface; `None` where the cell is wholly in or out.
    let mut index = vec![usize::MAX; (n as usize).pow(3)];
    let mut vertices = Vec::new();
    let cell_index = |x: i32, y: i32, z: i32| ((y * n + z) * n + x) as usize;

    for y in 0..n {
        for z in 0..n {
            for x in 0..n {
                let mut sum = [0.0; 3];
                let mut crossings = 0;
                let mut inside = false;
                let mut outside = false;
                for (a, b) in EDGES {
                    let da = grid.get(x + a[0], y + a[1], z + a[2]);
                    let db = grid.get(x + b[0], y + b[1], z + b[2]);
                    if da > 0.0 {
                        inside = true;
                    }
                    if da <= 0.0 {
                        outside = true;
                    }
                    if (da > 0.0) != (db > 0.0) {
                        let t = (da / (da - db)).clamp(0.0, 1.0) as f64;
                        sum[0] += a[0] as f64 + (b[0] - a[0]) as f64 * t;
                        sum[1] += a[1] as f64 + (b[1] - a[1]) as f64 * t;
                        sum[2] += a[2] as f64 + (b[2] - a[2]) as f64 * t;
                        crossings += 1;
                    }
                }
                if inside && outside && crossings > 0 {
                    let count = crossings as f64;
                    index[cell_index(x, y, z)] = vertices.len();
                    vertices.push([
                        x as f64 + sum[0] / count,
                        y as f64 + sum[1] / count,
                        z as f64 + sum[2] / count,
                    ]);
                }
            }
        }
    }

    let mut quads = Vec::with_capacity((n * n) as usize);
    // A quad per sign-changing lattice edge, joining the four cells that share it.
    for y in 0..=n {
        for z in 0..=n {
            for x in 0..=n {
                for axis in 0..3 {
                    let here = grid.get(x, y, z);
                    let (dx, dy, dz) = match axis {
                        0 => (1, 0, 0),
                        1 => (0, 1, 0),
                        _ => (0, 0, 1),
                    };
                    let next = grid.get(x + dx, y + dy, z + dz);
                    if (here > 0.0) == (next > 0.0) {
                        continue;
                    }
                    let mut points = [None; 4];
                    let mut complete = true;
                    for (slot, cell) in edge_cells(axis, x, y, z).iter().enumerate() {
                        if cell.iter().any(|value| *value < 0 || *value >= n) {
                            complete = false;
                            break;
                        }
                        let vertex = index[cell_index(cell[0], cell[1], cell[2])];
                        if vertex == usize::MAX {
                            complete = false;
                            break;
                        }
                        points[slot] = Some(vertices[vertex]);
                    }
                    if !complete {
                        continue;
                    }
                    let corners = [
                        points[0].unwrap(),
                        points[1].unwrap(),
                        points[2].unwrap(),
                        points[3].unwrap(),
                    ];
                    // Winding follows the density's sign, so the normal points out of the solid.
                    let corners = if here > 0.0 {
                        corners
                    } else {
                        [corners[3], corners[2], corners[1], corners[0]]
                    };
                    quads.push(MeshQuad {
                        corners,
                        normal: normal_of(corners),
                    });
                }
            }
        }
    }

    SectionMesh { quads, vertices }
}

/// The four cells that share the lattice edge along `axis` at `(x, y, z)`, in a consistent order
/// round the edge.
fn edge_cells(axis: usize, x: i32, y: i32, z: i32) -> [[i32; 3]; 4] {
    match axis {
        0 => [
            [x, y - 1, z - 1],
            [x, y, z - 1],
            [x, y, z],
            [x, y - 1, z],
        ],
        1 => [
            [x - 1, y, z - 1],
            [x - 1, y, z],
            [x, y, z],
            [x, y, z - 1],
        ],
        _ => [
            [x - 1, y - 1, z],
            [x, y - 1, z],
            [x, y, z],
            [x - 1, y, z],
        ],
    }
}

fn normal_of(corners: [[f64; 3]; 4]) -> [f64; 3] {
    let a = [
        corners[1][0] - corners[0][0],
        corners[1][1] - corners[0][1],
        corners[1][2] - corners[0][2],
    ];
    let b = [
        corners[3][0] - corners[0][0],
        corners[3][1] - corners[0][1],
        corners[3][2] - corners[0][2],
    ];
    let n = [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ];
    let length = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
    if length < 1.0e-9 {
        [0.0, 0.0, 0.0]
    } else {
        [n[0] / length, n[1] / length, n[2] / length]
    }
}

/// What the smoothing tool writes, and how hard it smooths.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SmoothSpec {
    /// How far from the clicked column the disc reaches, in blocks.
    pub radius: f64,
    /// `0.0` leaves the terrain alone, `1.0` takes the disc's mean height exactly.
    pub strength: f64,
    /// The block a raised column is filled with.
    pub fill: BlockId,
}

impl SmoothSpec {
    pub const fn new(radius: f64, strength: f64, fill: BlockId) -> Self {
        Self {
            radius,
            strength,
            fill,
        }
    }
}

/// The smoothed height of every column in the disc around `centre`.
///
/// The average is over the columns within `radius` (a Euclidean disc, as the Java tool's is), and
/// `strength` blends towards it, so a light brush takes the corners off a hill rather than flattening
/// it. Columns outside the disc keep their height.
pub fn smooth_heights(
    heights: &[i32],
    width: usize,
    depth: usize,
    centre: (usize, usize),
    spec: &SmoothSpec,
) -> Vec<i32> {
    let mut out = heights.to_vec();
    let (cx, cz) = (centre.0 as f64 + 0.5, centre.1 as f64 + 0.5);
    let radius = spec.radius.max(0.0);
    let strength = spec.strength.clamp(0.0, 1.0);

    for z in 0..depth {
        for x in 0..width {
            let dx = x as f64 + 0.5 - cx;
            let dz = z as f64 + 0.5 - cz;
            if dx * dx + dz * dz > radius * radius {
                continue;
            }
            let mut sum = 0.0;
            let mut count = 0.0;
            for nz in 0..depth {
                for nx in 0..width {
                    let ndx = nx as f64 + 0.5 - cx;
                    let ndz = nz as f64 + 0.5 - cz;
                    if ndx * ndx + ndz * ndz > radius * radius {
                        continue;
                    }
                    sum += heights[nz * width + nx] as f64;
                    count += 1.0;
                }
            }
            if count == 0.0 {
                continue;
            }
            let mean = sum / count;
            let current = heights[z * width + x] as f64;
            out[z * width + x] = (current + (mean - current) * strength).round() as i32;
        }
    }
    out
}

/// Smooths the terrain standing on `region`'s floor: reads each column's surface height, smooths it,
/// and fills or cuts the difference.
pub fn smooth<W: World>(
    world: &mut W,
    region: BlockBox,
    centre: (i32, i32),
    spec: &SmoothSpec,
) -> Vec<BlockChange> {
    let width = (region.max().x - region.min().x + 1) as usize;
    let depth = (region.max().z - region.min().z + 1) as usize;
    if width == 0 || depth == 0 {
        return Vec::new();
    }
    let mut heights = vec![region.min().y; width * depth];
    for z in 0..depth {
        for x in 0..width {
            let pos_x = region.min().x + x as i32;
            let pos_z = region.min().z + z as i32;
            let mut height = region.min().y;
            for y in (region.min().y..=region.max().y).rev() {
                if world.get(BlockPos::new(pos_x, y, pos_z)) != W::AIR {
                    height = y;
                    break;
                }
            }
            heights[z * width + x] = height;
        }
    }

    let centre = (
        (centre.0 - region.min().x).clamp(0, width as i32 - 1) as usize,
        (centre.1 - region.min().z).clamp(0, depth as i32 - 1) as usize,
    );
    let smoothed = smooth_heights(&heights, width, depth, centre, spec);
    let mut out = Vec::new();

    for z in 0..depth {
        for x in 0..width {
            let before = heights[z * width + x];
            let after = smoothed[z * width + x].clamp(region.min().y, region.max().y);
            if after == before {
                continue;
            }
            let pos_x = region.min().x + x as i32;
            let pos_z = region.min().z + z as i32;
            if after < before {
                for y in (after + 1)..=before {
                    if out.len() as u64 >= MAX_BLOCKS {
                        return out;
                    }
                    let pos = BlockPos::new(pos_x, y, pos_z);
                    let block = world.get(pos);
                    world.set(pos, W::AIR);
                    out.push(BlockChange {
                        pos,
                        before: block,
                        after: W::AIR,
                    });
                }
            } else {
                for y in (before + 1)..=after {
                    if out.len() as u64 >= MAX_BLOCKS {
                        return out;
                    }
                    let pos = BlockPos::new(pos_x, y, pos_z);
                    let block = world.get(pos);
                    world.set(pos, spec.fill);
                    out.push(BlockChange {
                        pos,
                        before: block,
                        after: spec.fill,
                    });
                }
            }
        }
    }
    out
}

/// A coarse XZ grid over a section's quads, for looking up the surface height at a point without
/// scanning the whole mesh.
///
/// A section's mesh is a few hundred quads; a trunk's rim samples a dozen points per log and a
/// scatter brush samples hundreds per click, so the linear scan adds up. Each quad is filed under
/// every cell of a coarse grid it overlaps, and a lookup then tests only that cell's few quads.
#[derive(Clone, Debug, Default)]
pub struct HeightIndex {
    cell: f64,
    min_x: f64,
    min_z: f64,
    cols: usize,
    rows: usize,
    buckets: Vec<Vec<u32>>,
    quads: Vec<MeshQuad>,
}

impl HeightIndex {
    /// Indexes `mesh` on cells `cell` blocks wide. A cell of 4 keeps a section's buckets small
    /// without making the index itself large.
    pub fn new(mesh: &SectionMesh, cell: f64) -> Self {
        let cell = if cell > 1.0e-6 { cell } else { 4.0 };
        let (mut min_x, mut min_z, mut max_x, mut max_z) = (
            f64::INFINITY,
            f64::INFINITY,
            f64::NEG_INFINITY,
            f64::NEG_INFINITY,
        );
        for quad in &mesh.quads {
            for corner in &quad.corners {
                min_x = min_x.min(corner[0]);
                min_z = min_z.min(corner[2]);
                max_x = max_x.max(corner[0]);
                max_z = max_z.max(corner[2]);
            }
        }
        if !min_x.is_finite() || !min_z.is_finite() {
            return Self::default();
        }
        let cols = (((max_x - min_x) / cell).ceil() as usize + 1).max(1);
        let rows = (((max_z - min_z) / cell).ceil() as usize + 1).max(1);
        let mut buckets = vec![Vec::new(); cols * rows];
        for (index, quad) in mesh.quads.iter().enumerate() {
            let (mut qx0, mut qx1, mut qz0, mut qz1) = (
                f64::INFINITY,
                f64::NEG_INFINITY,
                f64::INFINITY,
                f64::NEG_INFINITY,
            );
            for corner in &quad.corners {
                qx0 = qx0.min(corner[0]);
                qx1 = qx1.max(corner[0]);
                qz0 = qz0.min(corner[2]);
                qz1 = qz1.max(corner[2]);
            }
            let x0 = (((qx0 - min_x) / cell).floor() as isize).clamp(0, cols as isize - 1) as usize;
            let x1 = (((qx1 - min_x) / cell).floor() as isize).clamp(0, cols as isize - 1) as usize;
            let z0 = (((qz0 - min_z) / cell).floor() as isize).clamp(0, rows as isize - 1) as usize;
            let z1 = (((qz1 - min_z) / cell).floor() as isize).clamp(0, rows as isize - 1) as usize;
            for row in z0..=z1 {
                for col in x0..=x1 {
                    buckets[row * cols + col].push(index as u32);
                }
            }
            }
        Self {
            cell,
            min_x,
            min_z,
            cols,
            rows,
            buckets,
            quads: mesh.quads.clone(),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.quads.is_empty()
    }

    /// The surface height at `(x, z)`: the highest upward-facing quad whose plane covers the point.
    /// `fallback` where the point is outside the mesh.
    pub fn height(&self, x: f64, z: f64, fallback: f64) -> f64 {
        if self.quads.is_empty() {
            return fallback;
        }
        let col = ((x - self.min_x) / self.cell).floor();
        let row = ((z - self.min_z) / self.cell).floor();
        if !(0.0..self.cols as f64).contains(&col) || !(0.0..self.rows as f64).contains(&row) {
            return fallback;
        }
        let bucket = &self.buckets[row as usize * self.cols + col as usize];
        let mut best: Option<f64> = None;
        for index in bucket {
            let quad = &self.quads[*index as usize];
            if quad.normal[1].abs() < 0.2 {
                continue;
            }
            if let Some(y) = super::logs::quad_height_at(quad, x, z)
                && best.is_none_or(|current| y > current)
            {
                best = Some(y);
            }
        }
        best.unwrap_or(fallback)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[derive(Default)]
    struct W {
        cells: HashMap<BlockPos, BlockId>,
    }

    impl World for W {
        const AIR: BlockId = BlockId::new(0);
        fn get(&self, pos: BlockPos) -> BlockId {
            *self.cells.get(&pos).unwrap_or(&Self::AIR)
        }
        fn set(&mut self, pos: BlockPos, id: BlockId) {
            self.cells.insert(pos, id);
        }
    }

    const STONE: BlockId = BlockId::new(1);
    const DIRT: BlockId = BlockId::new(2);

    #[test]
    fn an_empty_field_makes_no_mesh() {
        let grid = DensityGrid::from_fn(8, |_, _, _| -1.0);
        let mesh = surface_nets(&grid);
        assert!(mesh.is_empty());
        assert!(mesh.vertices.is_empty());
        assert!(mesh.bounds().is_none());
    }

    #[test]
    fn the_height_index_agrees_with_a_linear_scan() {
        let grid = DensityGrid::from_fn(16, |x, y, z| {
            8.0 - y as f32 + (x as f32 * 0.35).sin() * 0.8 + (z as f32 * 0.2).cos() * 0.5
        });
        let mesh = surface_nets(&grid);
        assert!(!mesh.is_empty());
        let index = HeightIndex::new(&mesh, 4.0);
        assert!(!index.is_empty());

        let mut sampled = 0;
        for zi in 0..=32 {
            for xi in 0..=32 {
                let (x, z) = (xi as f64 * 0.5, zi as f64 * 0.5);
                let linear = super::super::logs::mesh_height(&mesh, x, z, -1.0);
                let indexed = index.height(x, z, -1.0);
                assert!(
                    (linear - indexed).abs() < 1.0e-9,
                    "at ({x}, {z}): scan {linear}, index {indexed}"
                );
                sampled += 1;
            }
        }
        assert!(sampled > 1_000);

        // Outside the mesh both answer with the fallback.
        assert_eq!(index.height(-40.0, -40.0, -1.0), -1.0);
    }

    #[test]
    fn a_flat_surface_makes_one_quad_per_cell_with_an_upward_normal() {
        let grid = DensityGrid::from_fn(8, |_, y, _| 7.5 - y as f32);
        let mesh = surface_nets(&grid);
        assert_eq!(mesh.quads.len(), 49, "7 x 7 cells of quads in an 8-cell field");
        assert!(!mesh.vertices.is_empty());
        for quad in &mesh.quads {
            assert!(
                (quad.normal[1] - 1.0).abs() < 1.0e-9,
                "normal {:?} should point up",
                quad.normal
            );
            for corner in quad.corners {
                assert!((corner[1] - 7.5).abs() < 1.0e-9, "the surface sits at 7.5");
            }
        }
        let (min, max) = mesh.bounds().unwrap();
        assert!((min[1] - 7.5).abs() < 1.0e-9 && (max[1] - 7.5).abs() < 1.0e-9);
    }

    #[test]
    fn a_block_in_the_middle_makes_a_closed_box() {
        let grid = DensityGrid::from_fn(8, |x, y, z| {
            let inside = (4..6).contains(&x) && (4..6).contains(&y) && (4..6).contains(&z);
            if inside { 1.0 } else { -1.0 }
        });
        let mesh = surface_nets(&grid);
        assert!(!mesh.is_empty());
        // A box has a face on each side: normals cover all six directions.
        let mut saw = [false; 6];
        for quad in &mesh.quads {
            let n = quad.normal;
            if n[1] > 0.9 {
                saw[0] = true;
            }
            if n[1] < -0.9 {
                saw[1] = true;
            }
            if n[0] > 0.9 {
                saw[2] = true;
            }
            if n[0] < -0.9 {
                saw[3] = true;
            }
            if n[2] > 0.9 {
                saw[4] = true;
            }
            if n[2] < -0.9 {
                saw[5] = true;
            }
        }
        assert_eq!(saw, [true; 6], "a closed box needs all six faces");
        let (min, max) = mesh.bounds().unwrap();
        assert!(min[0] >= 3.0 && max[0] <= 6.0, "the mesh stays near its block");
    }

    #[test]
    fn the_vertex_sits_where_the_surface_crosses() {
        // The surface is at 3.25 in the one cell that straddles it.
        let grid = DensityGrid::from_fn(8, |_, y, _| 3.25 - y as f32);
        let mesh = surface_nets(&grid);
        for vertex in &mesh.vertices {
            assert!((vertex[1] - 3.25).abs() < 1.0e-9, "vertex at {vertex:?}");
        }
    }

    #[test]
    fn a_flat_heightmap_needs_no_work() {
        let heights = vec![64; 25];
        let spec = SmoothSpec::new(3.0, 1.0, DIRT);
        let out = smooth_heights(&heights, 5, 5, (2, 2), &spec);
        assert_eq!(out, heights, "a flat floor has nothing to average");
    }

    #[test]
    fn a_spike_is_pulled_towards_its_neighbours() {
        let mut heights = vec![64; 25];
        heights[2 * 5 + 2] = 80;
        let spec = SmoothSpec::new(3.0, 1.0, DIRT);
        let out = smooth_heights(&heights, 5, 5, (2, 2), &spec);
        assert!(out[12] < 80, "the spike comes down");
        assert!(out[12] > 64, "but not all the way to the floor in one pass");
        // The disc's other columns are pulled up slightly by the spike.
        assert!(out[11] > 64, "the neighbours rise towards it");

        // Outside the disc nothing moves at all.
        let spec = SmoothSpec::new(1.0, 1.0, DIRT);
        let tight = smooth_heights(&heights, 5, 5, (0, 0), &spec);
        assert_eq!(tight[12], 80, "a one-block brush cannot reach the spike");
    }

    #[test]
    fn strength_blends_between_no_change_and_the_full_mean() {
        let mut heights = vec![64; 25];
        heights[12] = 74;
        let full = smooth_heights(&heights, 5, 5, (2, 2), &SmoothSpec::new(2.0, 1.0, DIRT));
        let none = smooth_heights(&heights, 5, 5, (2, 2), &SmoothSpec::new(2.0, 0.0, DIRT));
        let half = smooth_heights(&heights, 5, 5, (2, 2), &SmoothSpec::new(2.0, 0.5, DIRT));
        assert_eq!(none, heights);
        assert!(half[12] < 74 && half[12] > full[12]);
    }

    #[test]
    fn smoothing_a_spike_cuts_the_column_and_records_every_cell() {
        let mut world = W::default();
        for x in 0..5 {
            for z in 0..5 {
                for y in 60..=64 {
                    world.set(BlockPos::new(x, y, z), STONE);
                }
            }
        }
        for y in 65..=70 {
            world.set(BlockPos::new(2, y, 2), STONE);
        }

        let region = BlockBox::new(BlockPos::new(0, 56, 0), BlockPos::new(4, 72, 4));
        let changes = smooth(&mut world, region, (2, 2), &SmoothSpec::new(3.0, 1.0, DIRT));

        assert!(!changes.is_empty());
        assert!(changes.iter().all(|change| change.after == W::AIR), "a spike is only cut");
        let top = (57..=72)
            .rev()
            .find(|y| world.get(BlockPos::new(2, *y, 2)) != W::AIR)
            .unwrap();
        assert!(top < 70, "the spike is lower, at {top}");
        assert!(top >= 64, "but the floor survives");
    }

    #[test]
    fn a_hole_is_filled_with_the_spec_block() {
        let mut world = W::default();
        for x in 0..5 {
            for z in 0..5 {
                for y in 60..=64 {
                    world.set(BlockPos::new(x, y, z), STONE);
                }
            }
        }
        // A pit at the centre: its column ends lower.
        for y in 62..=64 {
            world.cells.remove(&BlockPos::new(2, y, 2));
        }

        let region = BlockBox::new(BlockPos::new(0, 56, 0), BlockPos::new(4, 70, 4));
        let changes = smooth(&mut world, region, (2, 2), &SmoothSpec::new(3.0, 1.0, DIRT));
        assert!(changes.iter().all(|change| change.after == DIRT), "a pit is filled");
        for y in 62..=64 {
            assert_eq!(world.get(BlockPos::new(2, y, 2)), DIRT, "the pit at {y} is filled");
        }
        assert_eq!(world.get(BlockPos::new(2, 65, 2)), W::AIR, "and the column is not raised");
    }

    #[test]
    fn the_cell_budget_bounds_a_smoothing_pass() {
        let mut world = W::default();
        for x in 0..5 {
            for z in 0..5 {
                for y in 40..=64 {
                    world.set(BlockPos::new(x, y, z), STONE);
                }
            }
        }
        for y in 65..=100 {
            world.set(BlockPos::new(2, y, 2), STONE);
        }
        let region = BlockBox::new(BlockPos::new(0, 30, 0), BlockPos::new(4, 110, 4));
        let changes = smooth(&mut world, region, (2, 2), &SmoothSpec::new(5.0, 1.0, DIRT));
        assert!(changes.len() as u64 <= MAX_BLOCKS);
    }
}
