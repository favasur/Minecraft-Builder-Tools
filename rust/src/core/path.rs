//! Linear objects: a road, path, wall or fence swept along a curve.
//!
//! This is the concept Narodnaya Karta calls a *linear object* and Hytale's creative tools call a
//! band: a polyline the builder draws, given a width, a thickness and a slot for each block layer
//! (surface, filler, edge). The mod had no equivalent — its only curved tools were an arch between
//! two points and an ellipse ring — so this module adds one.
//!
//! The curve is a Catmull–Rom spline through the drawn points, which needs no control handles and
//! passes through every point the builder clicks. It is sampled densely enough (0.5 blocks) that
//! the swept swath is always 4-connected, so a road never has a diagonal gap at a bend; a test
//! flood-fills the result to enforce that.
//!
//! Everything is in the XZ plane at a base height, which is the 3D equivalent of the 2D tools: the
//! width sweeps sideways, the thickness stacks the bands upward, and the curve only turns
//! horizontally.

use super::arch::Vec3;
use super::ops::{BlockChange, MAX_BLOCKS};
use super::pos::BlockPos;
use super::world::{BlockId, World};
use std::collections::HashMap;

/// Sampling step along the curve, in blocks.
const SAMPLE_STEP: f64 = 0.5;

/// Which layer of the cross-section a cell belongs to.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum PathBand {
    /// The body of the object, below the surface.
    Filler,
    /// The top layer the builder walks on.
    Surface,
    /// The side band: a kerb, a wall, a fence rail line. Takes priority so a kerb is never
    /// overwritten where a bend makes two lanes land on one cell.
    Edge,
}

/// The block slot for each band.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PathBands {
    pub surface: BlockId,
    pub filler: BlockId,
    /// `None` for a road with no kerb.
    pub edge: Option<BlockId>,
}

impl PathBands {
    /// A single-block object such as a fence or a path: everything is `block`.
    pub const fn single(block: BlockId) -> Self {
        Self {
            surface: block,
            filler: block,
            edge: None,
        }
    }

    /// A road of `surface` paving over `filler`, with an optional kerb of `edge`.
    pub const fn road(surface: BlockId, filler: BlockId, edge: Option<BlockId>) -> Self {
        Self {
            surface,
            filler,
            edge,
        }
    }
}

/// A linear object's cross-section, i.e. everything the sidebar would let the builder set.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PathStyle {
    pub bands: PathBands,
    /// Total width in blocks, at least 1.
    pub width: f64,
    /// Thickness in blocks, at least 1.
    pub height: i32,
    /// Width of the edge band on each side, in blocks. Only used when `bands.edge` is set.
    pub edge_width: f64,
}

impl PathStyle {
    /// A one-block-wide object: a fence line, a path, a cable run.
    pub const fn linear(bands: PathBands) -> Self {
        Self {
            bands,
            width: 1.0,
            height: 1,
            edge_width: 0.0,
        }
    }

    /// A road of `width` blocks, one layer thick, with a kerb of `edge_width` per side.
    pub const fn road(bands: PathBands, width: f64, edge_width: f64) -> Self {
        Self {
            bands,
            width,
            height: 1,
            edge_width,
        }
    }

    /// The same style with a different thickness.
    pub const fn with_height(mut self, height: i32) -> Self {
        self.height = height;
        self
    }
}

/// One point on the sampled curve: where it is and which way it runs.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Sample {
    pub pos: Vec3,
    /// The tangent, unnormalised (it scales with the parameterisation).
    pub tangent: Vec3,
}

/// Catmull–Rom through `points`, sampled roughly every `step` blocks.
///
/// The curve passes through every point, and a two-point input is the straight line between them
/// (the end tangents degenerate to the chord, so there is no overshoot).
pub fn samples(points: &[Vec3], step: f64) -> Vec<Sample> {
    if points.len() < 2 {
        return Vec::new();
    }
    let step = if step.is_finite() && step > 0.01 {
        step
    } else {
        SAMPLE_STEP
    };
    let n = points.len();
    let mut out = Vec::new();
    for i in 0..n - 1 {
        let p0 = points[i.saturating_sub(1)];
        let p1 = points[i];
        let p2 = points[i + 1];
        let p3 = points[(i + 2).min(n - 1)];
        let m1 = p2.sub(p0).scale(0.5);
        let m2 = p3.sub(p1).scale(0.5);

        let chord = p1.distance_to(p2);
        let steps = ((chord / step).ceil() as usize).max(1);
        for k in 0..steps {
            let t = k as f64 / steps as f64;
            out.push(Sample {
                pos: hermite(p1, m1, p2, m2, t),
                tangent: hermite_derivative(p1, m1, p2, m2, t),
            });
        }
    }
    let last = points[n - 1];
    out.push(Sample {
        pos: last,
        tangent: last.sub(points[n - 2]),
    });
    out
}

/// Cubic Hermite between `p1` and `p2` with tangents `m1`/`m2`.
fn hermite(p1: Vec3, m1: Vec3, p2: Vec3, m2: Vec3, t: f64) -> Vec3 {
    let t2 = t * t;
    let t3 = t2 * t;
    let h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    let h10 = t3 - 2.0 * t2 + t;
    let h01 = -2.0 * t3 + 3.0 * t2;
    let h11 = t3 - t2;
    p1.scale(h00)
        .add(m1.scale(h10))
        .add(p2.scale(h01))
        .add(m2.scale(h11))
}

/// Its derivative with respect to `t`.
fn hermite_derivative(p1: Vec3, m1: Vec3, p2: Vec3, m2: Vec3, t: f64) -> Vec3 {
    let t2 = t * t;
    let d00 = 6.0 * t2 - 6.0 * t;
    let d10 = 3.0 * t2 - 4.0 * t + 1.0;
    let d01 = -6.0 * t2 + 6.0 * t;
    let d11 = 3.0 * t2 - 2.0 * t;
    p1.scale(d00)
        .add(m1.scale(d10))
        .add(p2.scale(d01))
        .add(m2.scale(d11))
}

/// The cells a linear object occupies, with the band each belongs to.
///
/// Deterministic: sorted by `(x, y, z)`, and truncated at `MAX_BLOCKS` so one drawn path can never
/// exceed the mod's operation limit.
pub fn sweep(points: &[Vec3], style: &PathStyle, base_y: i32) -> Vec<(BlockPos, PathBand)> {
    let width = if style.width.is_finite() {
        style.width.max(1.0)
    } else {
        1.0
    };
    let half = width / 2.0;
    let lanes = (width.round().max(1.0)) as i32;
    let height = style.height.max(1);
    let edge_width = style.edge_width.max(0.0);

    // Best band per cell: the highest-priority one, so a kerb survives a bend landing on it.
    let mut best: HashMap<(i32, i32, i32), PathBand> = HashMap::new();
    for sample in samples(points, SAMPLE_STEP) {
        let (tx, tz) = (sample.tangent.x, sample.tangent.z);
        let len = (tx * tx + tz * tz).sqrt();
        if len < 1.0e-9 {
            continue;
        }
        // The sideways direction: the tangent turned 90 degrees in the XZ plane.
        let (nx, nz) = (tz / len, -tx / len);
        for lane in 0..lanes {
            let offset = if lanes == 1 {
                0.0
            } else {
                -half + 0.5 + lane as f64
            };
            let cx = (sample.pos.x + nx * offset).floor() as i32;
            let cz = (sample.pos.z + nz * offset).floor() as i32;
            let is_edge = style.bands.edge.is_some()
                && edge_width > 0.0
                && offset.abs() + 0.5 > half - edge_width;

            for layer in 0..height {
                let band = if is_edge {
                    PathBand::Edge
                } else if layer == height - 1 {
                    PathBand::Surface
                } else {
                    PathBand::Filler
                };
                let key = (cx, base_y + layer, cz);
                match best.get(&key) {
                    Some(current) if *current >= band => {}
                    _ => {
                        best.insert(key, band);
                    }
                }
            }
        }
    }

    let mut cells: Vec<(BlockPos, PathBand)> = best
        .into_iter()
        .map(|((x, y, z), band)| (BlockPos::new(x, y, z), band))
        .collect();
    cells.sort_unstable_by_key(|(pos, _)| (pos.x, pos.y, pos.z));
    cells.truncate(MAX_BLOCKS as usize);
    cells
}

impl PathBands {
    /// The block for `band`.
    pub fn block(&self, band: PathBand) -> BlockId {
        match band {
            PathBand::Surface => self.surface,
            PathBand::Filler => self.filler,
            PathBand::Edge => self.edge.unwrap_or(self.surface),
        }
    }
}

/// Lays the object into the world, recording every change for undo.
pub fn paint<W: World>(
    world: &mut W,
    points: &[Vec3],
    style: &PathStyle,
    base_y: i32,
) -> Vec<BlockChange> {
    let mut out = Vec::new();
    for (pos, band) in sweep(points, style, base_y) {
        let id = style.bands.block(band);
        let before = world.get(pos);
        world.set(pos, id);
        out.push(BlockChange {
            pos,
            before,
            after: id,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::{HashMap, HashSet};

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

    const PAVE: BlockId = BlockId::new(1);
    const GRAVEL: BlockId = BlockId::new(2);
    const KERB: BlockId = BlockId::new(3);
    const FENCE: BlockId = BlockId::new(4);

    fn straight() -> Vec<Vec3> {
        vec![Vec3::new(0.0, 0.0, 0.0), Vec3::new(8.0, 0.0, 0.0)]
    }

    #[test]
    fn a_straight_road_fills_one_lane_per_block_of_width() {
        let style = PathStyle::road(PathBands::road(PAVE, GRAVEL, None), 3.0, 0.0).with_height(2);
        let cells = sweep(&straight(), &style, 0);
        // 9 columns (x = 0..=8) x 3 lanes x 2 layers.
        assert_eq!(cells.len(), 54);

        let mut zs: Vec<i32> = cells.iter().map(|(p, _)| p.z).collect();
        zs.sort_unstable();
        zs.dedup();
        assert_eq!(zs, vec![-1, 0, 1]);

        for (pos, band) in &cells {
            assert!((0..=8).contains(&pos.x), "x {} off the path", pos.x);
            let expected = if pos.y == 1 {
                PathBand::Surface
            } else {
                PathBand::Filler
            };
            assert_eq!(*band, expected, "band at {pos:?}");
        }
    }

    #[test]
    fn the_edge_band_takes_the_outer_lanes_for_the_whole_thickness() {
        let style = PathStyle::road(PathBands::road(PAVE, GRAVEL, Some(KERB)), 5.0, 0.5)
            .with_height(2);
        let cells = sweep(&straight(), &style, 0);

        // Lanes at offset +-2 are the kerb; +-1 and 0 are paving and filler.
        for (pos, band) in &cells {
            let outer = pos.z.abs() == 2;
            if outer {
                assert_eq!(*band, PathBand::Edge, "kerb expected at {pos:?}");
            } else {
                let expected = if pos.y == 1 {
                    PathBand::Surface
                } else {
                    PathBand::Filler
                };
                assert_eq!(*band, expected, "body expected at {pos:?}");
            }
        }
    }

    #[test]
    fn a_fence_is_a_single_block_line() {
        let style = PathStyle::linear(PathBands::single(FENCE));
        let cells = sweep(&straight(), &style, 64);
        assert_eq!(cells.len(), 9);
        assert!(cells.iter().all(|(pos, _)| pos.y == 64 && pos.z == 0));
    }

    /// The invariant that matters for a road at a bend: no diagonal-only links. If a stride of the
    /// sweep left two lanes touching only at a corner, a builder would see a hole in the pavement.
    #[test]
    fn a_bent_road_stays_four_connected() {
        let style = PathStyle::road(PathBands::road(PAVE, GRAVEL, None), 3.0, 0.0);
        let points = vec![
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(8.0, 0.0, 0.0),
            Vec3::new(8.0, 0.0, 8.0),
        ];
        let cells = sweep(&points, &style, 0);
        let surface: HashSet<(i32, i32)> = cells
            .iter()
            .filter(|(_, band)| *band == PathBand::Surface)
            .map(|(pos, _)| (pos.x, pos.z))
            .collect();
        assert!(surface.len() > 40, "bent road too small: {}", surface.len());

        let start = *surface.iter().next().unwrap();
        let mut seen = HashSet::new();
        let mut stack = vec![start];
        while let Some((x, z)) = stack.pop() {
            if !seen.insert((x, z)) {
                continue;
            }
            for (dx, dz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
                let next = (x + dx, z + dz);
                if surface.contains(&next) && !seen.contains(&next) {
                    stack.push(next);
                }
            }
        }
        assert_eq!(seen.len(), surface.len(), "the road surface is not 4-connected");
    }

    #[test]
    fn the_sweep_is_deterministic_and_symmetric() {
        let style = PathStyle::road(PathBands::road(PAVE, GRAVEL, None), 3.0, 0.0);
        let a = sweep(&straight(), &style, 0);
        let b = sweep(&straight(), &style, 0);
        assert_eq!(a, b);

        // A road drawn the other way covers the same cells.
        let reversed = vec![Vec3::new(8.0, 0.0, 0.0), Vec3::new(0.0, 0.0, 0.0)];
        assert_eq!(sweep(&reversed, &style, 0), a);
    }

    #[test]
    fn painting_records_changes_and_writes_the_bands() {
        let mut world = W::default();
        let style = PathStyle::road(PathBands::road(PAVE, GRAVEL, Some(KERB)), 3.0, 0.5);
        let changes = paint(&mut world, &straight(), &style, 0);

        assert_eq!(changes.len(), 27);
        assert_eq!(world.get(BlockPos::new(4, 0, 0)), PAVE);
        assert_eq!(world.get(BlockPos::new(4, 0, 1)), KERB);
        assert_eq!(world.get(BlockPos::new(4, 0, -1)), KERB);
        assert!(changes.iter().all(|c| c.before == W::AIR));
    }

    #[test]
    fn a_curve_passes_through_every_drawn_point() {
        let points = vec![
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(4.0, 0.0, 3.0),
            Vec3::new(9.0, 0.0, 0.0),
        ];
        let sampled = samples(&points, 0.5);
        for point in &points {
            let hit = sampled
                .iter()
                .any(|s| s.pos.distance_to(*point) < 1.0e-9);
            assert!(hit, "curve misses {point:?}");
        }
    }

    #[test]
    fn two_points_give_the_straight_line_without_overshoot() {
        let sampled = samples(&straight(), 0.5);
        assert!(sampled.iter().all(|s| s.pos.y.abs() < 1.0e-12));
        assert!(sampled.iter().all(|s| (0.0..=8.0).contains(&s.pos.x)));
        assert!((sampled.last().unwrap().pos.x - 8.0).abs() < 1.0e-12);
    }
}
