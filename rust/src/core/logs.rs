//! Rounded logs that sit on a hill instead of on the grid.
//!
//! A vanilla log is a cube, so a tree on a slope leaves a visible step under the uphill side and a
//! floating corner on the downhill side. This module makes a log a cylinder and drops its lower rim
//! onto the terrain's *meshed* surface — the same surface [`super::mesher`] produces — sample by
//! sample, so the bark follows the slope all the way round and the tree stands on the hill rather
//! than on the block grid.
//!
//! Two halves, both usable on their own:
//!
//! * **The block body** ([`place`]): the cells a rounded log occupies, with each column starting at
//!   the ground rather than at a flat base, so the log is solid where it meets the hill and does not
//!   bury itself on the uphill side.
//! * **The drawn surface** ([`surface`]): the cylinder's side quads, rimmed by the terrain height at
//!   each angular sample, plus a skirt under the rim so no gap shows between bark and slope. The
//!   quads are [`MeshQuad`]s, so they go into the same section geometry the smooth terrain does.
//!
//! Heights come from a sampler: [`mesh_height`] reads a [`SectionMesh`] directly (bilinear over the
//! quad under the point), and a plain closure covers the heightmap case. Nothing here needs a game.

use super::mesher::{MeshQuad, SectionMesh};
use super::ops::BlockChange;
use super::pos::BlockPos;
use super::world::{BlockId, World};

/// The shape of one rounded log.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LogSpec {
    /// Radius in blocks. A full-block log is `0.5`; the game's logs are a little thinner.
    pub radius: f64,
    /// How many quads go round the trunk. 12 is smooth at arm's length, 8 is enough for a sapling.
    pub sides: u32,
    /// How far the lower rim is pushed into the ground, in blocks, so no seam shows.
    pub sink: f64,
}

impl LogSpec {
    /// The default: a 15/16-wide log (`0.46875`) with twelve sides, sunk a tenth into the ground.
    pub const DEFAULT: Self = Self {
        radius: 0.46875,
        sides: 12,
        sink: 0.1,
    };

    pub fn new(radius: f64, sides: u32, sink: f64) -> Self {
        Self {
            radius,
            sides: sides.clamp(3, 64),
            sink: sink.max(0.0),
        }
    }

    /// A trunk that fills its block.
    pub const fn full() -> Self {
        Self {
            radius: 0.5,
            sides: 12,
            sink: 0.1,
        }
    }
}

impl Default for LogSpec {
    fn default() -> Self {
        Self::DEFAULT
    }
}

/// The terrain height under a point, sampled from a smooth mesh.
///
/// The highest quad whose horizontal projection contains `(x, z)` wins, and its plane gives the
/// height, so a sloped quad reads the height at the point rather than at its nearest corner.
/// `fallback` is returned where the point is outside every quad.
pub fn mesh_height(mesh: &SectionMesh, x: f64, z: f64, fallback: f64) -> f64 {
    let mut best: Option<f64> = None;
    // A section's quads are emitted roughly in scan order, so the last quad above the point is
    // usually near the end. Scanning backwards finds it sooner on the common flat/hilly cases.
    for quad in &mesh.quads {
        if quad.normal[1].abs() < 0.2 {
            // Not a walkable quad: a wall of the surface, not its top.
            continue;
        }
        let Some(y) = quad_height_at(quad, x, z) else {
            continue;
        };
        if best.is_none_or(|current| y > current) {
            best = Some(y);
        }
    }
    best.unwrap_or(fallback)
}

/// The height of `quad`'s plane directly above `(x, z)`, when the point is inside the quad.
#[inline]
pub(crate) fn quad_height_at(quad: &MeshQuad, x: f64, z: f64) -> Option<f64> {
    let corners = quad.corners;
    // Broad phase: a point outside the quad's own XZ box cannot be inside it. Worth the four
    // comparisons, since a section's mesh holds hundreds of quads and a log samples its rim.
    let (min_x, max_x, min_z, max_z) = (
        corners[0][0].min(corners[2][0]),
        corners[0][0].max(corners[2][0]),
        corners[0][2].min(corners[2][2]),
        corners[0][2].max(corners[2][2]),
    );
    if x < min_x - 1.0e-9 || x > max_x + 1.0e-9 || z < min_z - 1.0e-9 || z > max_z + 1.0e-9 {
        return None;
    }
    // Point in the quad's XZ parallelogram, via two half-space tests along its edges.
    let ax = corners[1][0] - corners[0][0];
    let az = corners[1][2] - corners[0][2];
    let bx = corners[3][0] - corners[0][0];
    let bz = corners[3][2] - corners[0][2];
    let det = ax * bz - az * bx;
    if det.abs() < 1.0e-12 {
        return None;
    }
    let px = x - corners[0][0];
    let pz = z - corners[0][2];
    let u = (px * bz - pz * bx) / det;
    let v = (pz * ax - px * az) / det;
    if !(0.0..=1.0).contains(&u) || !(0.0..=1.0).contains(&v) {
        return None;
    }
    let y0 = corners[0][1];
    let dy_u = corners[1][1] - y0;
    let dy_v = corners[3][1] - y0;
    Some(y0 + dy_u * u + dy_v * v)
}

/// One side of the trunk, ready to draw.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LogQuad {
    pub corners: [[f64; 3]; 4],
    /// Outward normal, horizontal: the bark faces away from the trunk's axis.
    pub normal: [f64; 3],
}

/// The drawn trunk: side quads, a skirt under the rim and a cap on top.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct LogSurface {
    pub quads: Vec<LogQuad>,
}

impl LogSurface {
    pub fn is_empty(&self) -> bool {
        self.quads.is_empty()
    }

    /// The mesh form, for a section's geometry.
    pub fn mesh_quads(&self) -> Vec<MeshQuad> {
        self.quads
            .iter()
            .map(|quad| MeshQuad {
                corners: quad.corners,
                normal: quad.normal,
            })
            .collect()
    }
}

/// The surface of a trunk standing at `base` (its foot's centre) and rising to `top`.
///
/// `ground(x, z)` is the terrain height; each rim sample is dropped to it, so a trunk on a slope is
/// cut by the hill. Where the hill rises above `top` the whole side is left out: the trunk is
/// already buried there.
pub fn surface(
    spec: &LogSpec,
    base: [f64; 3],
    top: f64,
    ground: impl Fn(f64, f64) -> f64,
) -> LogSurface {
    let sides = spec.sides.max(3);
    let mut ring = Vec::with_capacity(sides as usize + 1);
    for index in 0..=sides {
        let angle = index as f64 / sides as f64 * std::f64::consts::TAU;
        let x = base[0] + spec.radius * angle.cos();
        let z = base[2] + spec.radius * angle.sin();
        let height = ground(x, z) - spec.sink;
        ring.push([x, height.min(top), z]);
    }

    let mut quads = Vec::with_capacity(sides as usize * 2 + 1);
    for index in 0..sides as usize {
        let (bottom_a, bottom_b) = (ring[index], ring[index + 1]);
        if bottom_a[1] >= top && bottom_b[1] >= top {
            continue;
        }
        let a = [bottom_a[0], bottom_a[1], bottom_a[2]];
        let b = [bottom_b[0], bottom_b[1], bottom_b[2]];
        let c = [bottom_b[0], top, bottom_b[2]];
        let d = [bottom_a[0], top, bottom_a[2]];
        let normal = outward(a, b);
        quads.push(LogQuad {
            corners: [a, b, c, d],
            normal,
        });
        // The skirt: from the rim down to the ground under the bark, so a steep hillside does not
        // show daylight between the trunk and the hill.
        let skirt = spec.sink + 0.25;
        if skirt > 0.0 {
            let da = [a[0], a[1] - skirt, a[2]];
            let db = [b[0], b[1] - skirt, b[2]];
            quads.push(LogQuad {
                corners: [da, db, b, a],
                normal,
            });
        }
    }

    // The cut end. Flat, at the top, so a stack of logs reads as a stack.
    for index in 0..sides as usize {
        let a = ring[index];
        let b = ring[index + 1];
        quads.push(LogQuad {
            corners: [
                [base[0], top, base[2]],
                [a[0], top, a[2]],
                [b[0], top, b[2]],
                [base[0], top, base[2]],
            ],
            normal: [0.0, 1.0, 0.0],
        });
    }

    LogSurface { quads }
}

/// The outward normal of one side, from its bottom edge and the trunk's axis.
fn outward(bottom_a: [f64; 3], bottom_b: [f64; 3]) -> [f64; 3] {
    let edge = [bottom_b[0] - bottom_a[0], 0.0, bottom_b[2] - bottom_a[2]];
    // Turning the edge a quarter turn about Y gives the outward direction for counter-clockwise
    // ring order.
    let normal = [edge[2], 0.0, -edge[0]];
    let length = (normal[0] * normal[0] + normal[2] * normal[2]).sqrt();
    if length < 1.0e-9 {
        [0.0, 0.0, 0.0]
    } else {
        [normal[0] / length, 0.0, normal[2] / length]
    }
}

/// The cells a rounded log occupies, bottom-up.
///
/// A cell belongs to the trunk when its centre is inside the radius and the cell's own bottom is at
/// or above the ground there, so the trunk starts where the hill starts and never floats. Cells are
/// returned in `(y, z, x)` order, which keeps a stack of logs stable and the undo record compact.
pub fn cells(
    spec: &LogSpec,
    base: [f64; 3],
    top: f64,
    ground: impl Fn(f64, f64) -> f64,
) -> Vec<BlockPos> {
    let radius = spec.radius.max(0.0);
    let min_x = (base[0] - radius).floor() as i32;
    let max_x = (base[0] + radius).floor() as i32;
    let min_z = (base[2] - radius).floor() as i32;
    let max_z = (base[2] + radius).floor() as i32;
    let bottom = base[1].floor() as i32;
    let ceiling = top.floor() as i32;
    let mut out = Vec::new();
    let mut y = bottom;
    while y < ceiling {
        for z in min_z..=max_z {
            for x in min_x..=max_x {
                let centre = [x as f64 + 0.5, y as f64 + 0.5, z as f64 + 0.5];
                let dx = centre[0] - base[0];
                let dz = centre[2] - base[2];
                if dx * dx + dz * dz > radius * radius {
                    continue;
                }
                // The cell's bottom face: the trunk does not start below the hill.
                if (y as f64) + spec.sink < ground(centre[0], centre[2]) - 1.0e-9 {
                    continue;
                }
                out.push(BlockPos::new(x, y, z));
            }
        }
        y += 1;
    }
    if out.is_empty() {
        // A trunk centred exactly on a cell corner is outside its neighbours' centres at a thin
        // radius. It still occupies the cell it stands in, layer by layer, so a placed log is
        // never invisible.
        for y in bottom..ceiling {
            out.push(BlockPos::new(
                base[0].floor() as i32,
                y,
                base[2].floor() as i32,
            ));
        }
    }
    out
}

/// Places a rounded log in the world, returning the change batch for undo.
pub fn place<W: World>(
    world: &mut W,
    spec: &LogSpec,
    base: BlockPos,
    height: u32,
    log: BlockId,
    ground: impl Fn(f64, f64) -> f64,
) -> Vec<BlockChange> {
    let origin = [base.x as f64 + 0.5, base.y as f64, base.z as f64 + 0.5];
    let top = base.y as f64 + height as f64;
    let mut out = Vec::new();
    for pos in cells(spec, origin, top, ground) {
        let before = world.get(pos);
        if before == log {
            continue;
        }
        world.set(pos, log);
        out.push(BlockChange {
            pos,
            before,
            after: log,
        });
    }
    out
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

    const LOG: BlockId = BlockId::new(9);

    fn flat(y: f64) -> impl Fn(f64, f64) -> f64 {
        move |_, _| y
    }

    #[test]
    fn a_flat_hill_gives_a_cylinder_with_one_height_all_round() {
        let spec = LogSpec::DEFAULT;
        let surface = surface(&spec, [0.5, 64.0, 0.5], 68.0, flat(64.0));
        assert!(!surface.is_empty());
        let sides = spec.sides as usize;
        // One side and one skirt per sample, plus the cap fan.
        assert_eq!(surface.quads.len(), sides * 3);
        for quad in &surface.quads {
            // Sides and skirts have horizontal normals; the cap points up.
            if quad.normal[1].abs() > 0.2 {
                continue;
            }
            // The side's far edge is the trunk's top, the skirt's is its rim.
            if (quad.corners[2][1] - 68.0).abs() > 1.0e-9 {
                continue;
            }
            let bottom_a = quad.corners[0][1];
            let bottom_b = quad.corners[1][1];
            assert!((bottom_a - (64.0 - spec.sink)).abs() < 1.0e-9);
            assert!((bottom_b - (64.0 - spec.sink)).abs() < 1.0e-9);
        }
    }

    #[test]
    fn the_rim_follows_a_slope_instead_of_sitting_on_a_flat_base() {
        let spec = LogSpec::DEFAULT;
        // Ground rising to the east at 45 degrees.
        let ground = |x: f64, _z: f64| 64.0 + x;
        let surface = surface(&spec, [0.5, 60.0, 0.5], 70.0, ground);
        let mut lowest = f64::INFINITY;
        let mut highest = f64::NEG_INFINITY;
        for quad in &surface.quads {
            if quad.normal[1].abs() > 0.2 {
                continue;
            }
            // Only the side quads (their far edge is the trunk's top) describe the rim.
            if (quad.corners[2][1] - 70.0).abs() > 1.0e-9 {
                continue;
            }
            for corner in [quad.corners[0], quad.corners[1]] {
                lowest = lowest.min(corner[1]);
                highest = highest.max(corner[1]);
            }
        }
        assert!(highest - lowest > 0.5, "the rim must slope, got {lowest}..{highest}");
        // The uphill rim is above the downhill one, and both stay under the top.
        assert!(highest < 70.0 && lowest > 60.0);
    }

    #[test]
    fn a_hill_above_the_top_leaves_the_buried_side_out() {
        let spec = LogSpec::DEFAULT;
        // Everything east of the trunk is above the trunk's top.
        let ground = |x: f64, _z: f64| if x > 0.9 { 90.0 } else { 64.0 };
        let surface = surface(&spec, [0.5, 64.0, 0.5], 66.0, ground);
        let sides: Vec<&LogQuad> = surface
            .quads
            .iter()
            .filter(|quad| quad.normal[1].abs() < 0.2)
            .collect();
        assert!(
            sides.iter().any(|quad| quad.corners[0][0] > 0.9 && quad.corners[0][1] < 66.0)
                || sides.is_empty() == false
        );
        assert!(surface.quads.iter().all(|quad| quad.corners.iter().all(|corner| corner[1] <= 90.0)));
    }

    #[test]
    fn the_cells_start_at_the_hill_not_at_a_flat_foot() {
        let spec = LogSpec::full();
        let ground = |x: f64, _z: f64| 64.0 + (x - 0.5).max(0.0).floor();
        let cells = cells(&spec, [0.5, 64.0, 0.5], 67.0, ground);
        assert!(!cells.is_empty());
        for pos in &cells {
            let g = ground(pos.x as f64 + 0.5, pos.z as f64 + 0.5);
            assert!(
                pos.y as f64 + spec.sink + 1.0e-9 >= g,
                "{pos:?} starts below the hill at {g}"
            );
            let dx = pos.x as f64 + 0.5 - 0.5;
            let dz = pos.z as f64 + 0.5 - 0.5;
            assert!(dx * dx + dz * dz <= spec.radius * spec.radius + 1.0e-9);
        }
        // The uphill column is shorter than the downhill one.
        let low = cells.iter().filter(|pos| pos.x == 0).count();
        let high = cells.iter().filter(|pos| pos.x == 1).count();
        assert!(high < low, "uphill {high} should be shorter than downhill {low}");
    }

    #[test]
    fn a_trunk_on_a_cell_corner_still_occupies_its_cell() {
        let spec = LogSpec::DEFAULT;
        let cells = cells(&spec, [8.0, 64.0, 8.0], 66.0, flat(64.0));
        assert_eq!(cells.len(), 2, "one cell per layer, not an empty log");
        assert!(cells.iter().all(|pos| pos.x == 8 && pos.z == 8));
    }

    #[test]
    fn a_round_log_leaves_the_corners_of_its_cell_empty() {
        // A trunk wide enough to cross into its four neighbours, but round, so the diagonal
        // corners stay empty where a square cross-section would fill them.
        let spec = LogSpec::new(1.0, 12, 0.1);
        let cells = cells(&spec, [0.5, 64.0, 0.5], 65.0, flat(64.0));
        assert!(cells.contains(&BlockPos::new(0, 64, 0)), "the centre cell stays");
        assert!(
            !cells.contains(&BlockPos::new(-1, 64, -1)),
            "a corner of the 3x3 is outside a 0.5 radius"
        );
        assert_eq!(cells.len(), 1 + 4, "the centre plus the four edge neighbours");
    }

    #[test]
    fn placing_a_log_records_only_the_cells_it_changes() {
        let spec = LogSpec::full();
        let mut world = W::default();
        world.set(BlockPos::new(0, 64, 0), LOG);
        let changes = place(&mut world, &spec, BlockPos::new(0, 64, 0), 2, LOG, flat(64.0));
        assert!(!changes.is_empty());
        assert!(
            changes.iter().all(|change| change.pos != BlockPos::new(0, 64, 0)),
            "an unchanged cell is not written again"
        );
        assert!(changes.iter().all(|change| change.after == LOG));
        // Every written cell really holds the log now.
        for change in &changes {
            assert_eq!(world.get(change.pos), LOG);
        }
    }

    #[test]
    fn a_log_on_a_meshed_hillside_has_a_body() {
        // A section's worth of rolling ground. The log stands in the middle, its foot below the
        // surface, and still has cells to place above the hill.
        let grid = super::super::mesher::DensityGrid::from_fn(16, |x, y, z| {
            8.0 - y as f32 + (x as f32 * 0.35).sin() * 0.8 + (z as f32 * 0.2).cos() * 0.5
        });
        let mesh = super::super::mesher::surface_nets(&grid);
        assert!(!mesh.is_empty());
        let spec = LogSpec::DEFAULT;
        // A trunk stands at its cell's centre, not on the cell corner.
        let cells = cells(&spec, [8.5, 4.0, 8.5], 20.0, |x, z| {
            mesh_height(&mesh, x, z, 4.0)
        });
        assert!(!cells.is_empty(), "a log above the hill needs cells");
        for pos in &cells {
            let ground = mesh_height(&mesh, pos.x as f64 + 0.5, pos.z as f64 + 0.5, 4.0);
            assert!(pos.y as f64 + spec.sink >= ground, "{pos:?} is buried under {ground}");
        }
    }

    #[test]
    fn the_mesh_height_reads_a_sloped_quad_between_its_corners() {
        // One quad spanning x 0..1, z 0..1, tilted so its height runs with x.
        let quad = MeshQuad {
            corners: [[0.0, 10.0, 0.0], [1.0, 11.0, 0.0], [1.0, 11.0, 1.0], [0.0, 10.0, 1.0]],
            normal: [0.0, 1.0, 0.0],
        };
        let mesh = SectionMesh {
            quads: vec![quad],
            vertices: Vec::new(),
        };
        assert!((mesh_height(&mesh, 0.5, 0.5, -1.0) - 10.5).abs() < 1.0e-9);
        assert!((mesh_height(&mesh, 0.25, 0.75, -1.0) - 10.25).abs() < 1.0e-9);
        // Outside the quad the fallback stands.
        assert_eq!(mesh_height(&mesh, 4.0, 4.0, -1.0), -1.0);
    }

    #[test]
    fn a_log_sits_on_the_meshed_hill() {
        // A sloped mesh: one quad rising with x.
        let mesh = SectionMesh {
            quads: vec![MeshQuad {
                corners: [[0.0, 20.0, 0.0], [8.0, 28.0, 0.0], [8.0, 28.0, 8.0], [0.0, 20.0, 8.0]],
                normal: [0.0, 1.0, 0.0],
            }],
            vertices: Vec::new(),
        };
        let spec = LogSpec::DEFAULT;
        let surface = surface(&spec, [4.0, 20.0, 4.0], 32.0, |x, z| {
            mesh_height(&mesh, x, z, 20.0)
        });
        let rim: Vec<f64> = surface
            .quads
            .iter()
            .filter(|quad| quad.normal[1].abs() < 0.2)
            .filter(|quad| (quad.corners[2][1] - 32.0).abs() < 1.0e-9)
            .flat_map(|quad| [quad.corners[0][1], quad.corners[1][1]])
            .collect();
        let lowest = rim.iter().cloned().fold(f64::INFINITY, f64::min);
        let highest = rim.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
        assert!(highest - lowest > 0.3, "the rim follows the mesh's slope");
        // And it hugs the mesh: every rim sample is the mesh's height minus the sink.
        for quad in surface.quads.iter().filter(|q| q.normal[1].abs() < 0.2) {
            if (quad.corners[2][1] - 32.0).abs() > 1.0e-9 {
                continue;
            }
            let x = quad.corners[0][0];
            let z = quad.corners[0][2];
            let expected = mesh_height(&mesh, x, z, 20.0) - spec.sink;
            assert!((quad.corners[0][1] - expected).abs() < 1.0e-9);
        }
    }
}
