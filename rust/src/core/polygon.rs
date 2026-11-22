//! Territorial objects: an arbitrary polygon on the XZ plane.
//!
//! Narodnaya Karta's polygon tools let you mark out a territory with a border, a fill and a
//! hatch pattern. The 3D translation keeps the same three parts and adds a thickness: a polygon is
//! resolved to cell centres in the XZ plane, its border is the band within `border.width` of an
//! edge, and the interior is either solid or diagonally hatched.
//!
//! Containment is the even-odd crossing rule, so concave and self-overlapping outlines behave the
//! way a drawn polygon does, and the border width is a true distance to the nearest edge segment
//! (not an inward offset of each edge), so thin diagonal slivers do not get a doubled border.

use super::ops::{BlockChange, MAX_BLOCKS};
use super::pos::BlockPos;
use super::world::{BlockId, World};

/// A closed polygon on the XZ plane, in world coordinates.
#[derive(Clone, Debug, PartialEq)]
pub struct Polygon {
    points: Vec<(f64, f64)>,
}

impl Polygon {
    /// A polygon from at least three points. Degenerate outlines (zero area) are rejected, because
    /// filling them would write an arbitrary line of blocks.
    pub fn new(points: Vec<(f64, f64)>) -> Option<Self> {
        if points.len() < 3 {
            return None;
        }
        if (shoelace(&points) as f64).abs() < 1.0e-9 {
            return None;
        }
        Some(Self { points })
    }

    pub fn points(&self) -> &[(f64, f64)] {
        &self.points
    }

    /// Even-odd containment test for a point in the XZ plane.
    pub fn contains(&self, x: f64, z: f64) -> bool {
        let mut inside = false;
        let n = self.points.len();
        let mut j = n - 1;
        for i in 0..n {
            let (xi, zi) = self.points[i];
            let (xj, zj) = self.points[j];
            if (zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi {
                inside = !inside;
            }
            j = i;
        }
        inside
    }

    /// True when the cell whose centre is `(x, z)` lies in the polygon.
    pub fn contains_cell(&self, x: i32, z: i32) -> bool {
        self.contains(x as f64 + 0.5, z as f64 + 0.5)
    }

    /// Distance from a point to the nearest edge segment.
    pub fn distance_to_edge(&self, x: f64, z: f64) -> f64 {
        let n = self.points.len();
        let mut best = f64::INFINITY;
        let mut j = n - 1;
        for i in 0..n {
            let (ax, az) = self.points[j];
            let (bx, bz) = self.points[i];
            best = best.min(segment_distance(ax, az, bx, bz, x, z));
            j = i;
        }
        best
    }

    /// Signed area in blocks squared (positive for counter-clockwise outlines).
    pub fn area(&self) -> f64 {
        shoelace(&self.points) as f64
    }

    /// `(min_x, min_z, max_x, max_z)`.
    pub fn bounds(&self) -> (f64, f64, f64, f64) {
        let mut b = (
            f64::INFINITY,
            f64::INFINITY,
            f64::NEG_INFINITY,
            f64::NEG_INFINITY,
        );
        for (x, z) in &self.points {
            b.0 = b.0.min(*x);
            b.1 = b.1.min(*z);
            b.2 = b.2.max(*x);
            b.3 = b.3.max(*z);
        }
        b
    }
}

fn shoelace(points: &[(f64, f64)]) -> f64 {
    let n = points.len();
    let mut sum = 0.0;
    let mut j = n - 1;
    for i in 0..n {
        sum += (points[j].0 - points[i].0) * (points[j].1 + points[i].1);
        j = i;
    }
    sum / 2.0
}

fn segment_distance(ax: f64, az: f64, bx: f64, bz: f64, px: f64, pz: f64) -> f64 {
    let (dx, dz) = (bx - ax, bz - az);
    let len2 = dx * dx + dz * dz;
    if len2 < 1.0e-12 {
        return ((px - ax).powi(2) + (pz - az).powi(2)).sqrt();
    }
    let t = (((px - ax) * dx + (pz - az) * dz) / len2).clamp(0.0, 1.0);
    let (cx, cz) = (ax + t * dx, az + t * dz);
    ((px - cx).powi(2) + (pz - cz).powi(2)).sqrt()
}

/// A diagonal hatch for the interior: every `period` blocks, `width` of them take `block`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Hatch {
    pub block: BlockId,
    pub period: i32,
    pub width: i32,
}

impl Hatch {
    /// Whether the cell at `(x, z)` is a hatch stripe. Diagonal, so a territory reads as hatching
    /// from any viewing angle rather than as rows.
    pub fn covers(&self, x: i32, z: i32) -> bool {
        let period = self.period.max(1);
        let width = self.width.clamp(1, period);
        (x + z).rem_euclid(period) < width
    }
}

/// A border band `width` blocks wide, measured from the outline.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Border {
    pub block: BlockId,
    pub width: f64,
}

/// What a territory is made of: any of the three parts may be absent.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Territory {
    pub border: Option<Border>,
    /// The interior's base block. `None` with a hatch gives hatching on the existing surface.
    pub fill: Option<BlockId>,
    pub hatch: Option<Hatch>,
    /// Thickness in blocks; 1 writes only the top layer.
    pub depth: i32,
}

impl Territory {
    /// A filled area with a border, the default a polygon tool would offer.
    pub const fn outlined(fill: BlockId, border: Border) -> Self {
        Self {
            border: Some(border),
            fill: Some(fill),
            hatch: None,
            depth: 1,
        }
    }

    /// Border only, for marking an outline without touching the interior.
    pub const fn border_only(border: Border) -> Self {
        Self {
            border: Some(border),
            fill: None,
            hatch: None,
            depth: 1,
        }
    }

    pub const fn with_hatch(mut self, hatch: Hatch) -> Self {
        self.hatch = Some(hatch);
        self
    }

    pub const fn with_depth(mut self, depth: i32) -> Self {
        self.depth = depth;
        self
    }

    /// The block a cell should hold, or `None` to leave it alone.
    pub fn block_at(&self, polygon: &Polygon, x: i32, z: i32) -> Option<BlockId> {
        let (cx, cz) = (x as f64 + 0.5, z as f64 + 0.5);
        if !polygon.contains(cx, cz) {
            return None;
        }
        if let Some(border) = self.border
            && border.width > 0.0
            && polygon.distance_to_edge(cx, cz) <= border.width
        {
            return Some(border.block);
        }
        if let Some(hatch) = self.hatch
            && hatch.covers(x, z)
        {
            return Some(hatch.block);
        }
        self.fill
    }
}

/// Lays the territory into the world from `top_y` downwards, recording every change for undo.
pub fn paint<W: World>(
    world: &mut W,
    polygon: &Polygon,
    territory: &Territory,
    top_y: i32,
) -> Vec<BlockChange> {
    let (min_x, min_z, max_x, max_z) = polygon.bounds();
    let depth = territory.depth.max(1);
    let mut out = Vec::new();

    for x in (min_x.floor() as i32)..=(max_x.ceil() as i32) {
        for z in (min_z.floor() as i32)..=(max_z.ceil() as i32) {
            let Some(id) = territory.block_at(polygon, x, z) else {
                continue;
            };
            for layer in 0..depth {
                if out.len() as u64 >= MAX_BLOCKS {
                    return out;
                }
                let pos = BlockPos::new(x, top_y - layer, z);
                let before = world.get(pos);
                world.set(pos, id);
                out.push(BlockChange {
                    pos,
                    before,
                    after: id,
                });
            }
        }
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

    const GRASS: BlockId = BlockId::new(1);
    const STONE: BlockId = BlockId::new(2);
    const WOOL: BlockId = BlockId::new(3);

    fn square() -> Polygon {
        Polygon::new(vec![(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0)]).unwrap()
    }

    /// A concave L: 6x2 plus 2x4, area 20.
    fn elbow() -> Polygon {
        Polygon::new(vec![
            (0.0, 0.0),
            (6.0, 0.0),
            (6.0, 2.0),
            (2.0, 2.0),
            (2.0, 6.0),
            (0.0, 6.0),
        ])
        .unwrap()
    }

    #[test]
    fn containment_follows_the_drawn_outline() {
        let square = square();
        assert!(square.contains(5.0, 5.0));
        assert!(!square.contains(-1.0, 5.0));
        assert!(!square.contains(5.0, 11.0));
        assert!(square.contains_cell(0, 0));
        assert!(!square.contains_cell(10, 10));
    }

    #[test]
    fn a_concave_polygon_excludes_its_notch() {
        let elbow = elbow();
        assert!((elbow.area().abs() - 20.0).abs() < 1.0e-9);
        // Inside the wide arm and the tall arm.
        assert!(elbow.contains(1.0, 1.0));
        assert!(elbow.contains(5.0, 1.0));
        assert!(elbow.contains(1.0, 5.0));
        // In the notch, which must stay empty.
        assert!(!elbow.contains(4.0, 4.0));
        assert!(!elbow.contains(5.0, 5.0));

        let filled = (0..8)
            .flat_map(|x| (0..8).map(move |z| (x, z)))
            .filter(|(x, z)| elbow.contains_cell(*x, *z))
            .count();
        assert_eq!(filled, 20);
    }

    #[test]
    fn a_fill_covers_the_area_and_the_border_ring_is_one_cell_thick() {
        let mut world = W::default();
        let territory = Territory {
            border: Some(Border {
                block: STONE,
                width: 1.0,
            }),
            fill: Some(GRASS),
            hatch: None,
            depth: 1,
        };
        let changes = paint(&mut world, &square(), &territory, 0);
        assert_eq!(changes.len(), 100);
        // 100 cells total, 8x8 interior left after the 1-block ring.
        let border = (0..10)
            .flat_map(|x| (0..10).map(move |z| (x, z)))
            .filter(|(x, z)| {
                world.get(BlockPos::new(*x, 0, *z)) == STONE
            })
            .count();
        assert_eq!(border, 36);
        assert_eq!(world.get(BlockPos::new(5, 0, 5)), GRASS);
    }

    #[test]
    fn a_wider_border_thickens_the_ring() {
        let mut world = W::default();
        let territory = Territory {
            border: Some(Border {
                block: STONE,
                width: 2.0,
            }),
            fill: Some(GRASS),
            hatch: None,
            depth: 1,
        };
        paint(&mut world, &square(), &territory, 0);
        // The border is a distance test on cell centres, so a 2-block band is 2 cells: centres at
        // 1.5 are inside it, centres at 2.5 are not. That leaves a 6x6 interior.
        assert_eq!(world.get(BlockPos::new(1, 0, 6)), STONE);
        assert_eq!(world.get(BlockPos::new(1, 0, 1)), STONE);
        assert_eq!(world.get(BlockPos::new(2, 0, 6)), GRASS);
        assert_eq!(world.get(BlockPos::new(2, 0, 2)), GRASS);
        assert_eq!(world.get(BlockPos::new(5, 0, 5)), GRASS);
    }

    #[test]
    fn border_only_leaves_the_interior_untouched() {
        let mut world = W::default();
        for x in 0..10 {
            for z in 0..10 {
                world.set(BlockPos::new(x, 0, z), GRASS);
            }
        }
        let territory = Territory::border_only(Border {
            block: STONE,
            width: 1.0,
        });
        let changes = paint(&mut world, &square(), &territory, 0);
        assert_eq!(changes.len(), 36);
        assert_eq!(world.get(BlockPos::new(5, 0, 5)), GRASS);
        assert_eq!(world.get(BlockPos::new(0, 0, 5)), STONE);
    }

    #[test]
    fn hatching_runs_diagonally_across_the_interior() {
        let mut world = W::default();
        let territory = Territory::outlined(
            GRASS,
            Border {
                block: STONE,
                width: 1.0,
            },
        )
        .with_hatch(Hatch {
            block: WOOL,
            period: 4,
            width: 2,
        });
        paint(&mut world, &square(), &territory, 0);

        // (3+3) % 4 == 2 and (5+5) % 4 == 2: outside the 2-wide stripe, so plain fill.
        assert_eq!(world.get(BlockPos::new(3, 0, 3)), GRASS);
        assert_eq!(world.get(BlockPos::new(5, 0, 5)), GRASS);
        // (3+1) % 4 == 0: inside the stripe, and far enough from the edge to not be border.
        assert_eq!(world.get(BlockPos::new(3, 0, 1)), WOOL);
        // The stripes are diagonal: swapping x and z gives the same answer.
        let a = Hatch {
            block: WOOL,
            period: 4,
            width: 2,
        }
        .covers(4, 0);
        let b = Hatch {
            block: WOOL,
            period: 4,
            width: 2,
        }
        .covers(0, 4);
        assert_eq!(a, b);
    }

    #[test]
    fn depth_stacks_the_territory_downwards() {
        let mut world = W::default();
        let territory = Territory::outlined(
            GRASS,
            Border {
                block: STONE,
                width: 1.0,
            },
        )
        .with_depth(3);
        let changes = paint(&mut world, &square(), &territory, 10);
        assert_eq!(changes.len(), 300);
        assert_eq!(world.get(BlockPos::new(5, 10, 5)), GRASS);
        assert_eq!(world.get(BlockPos::new(5, 9, 5)), GRASS);
        assert_eq!(world.get(BlockPos::new(5, 8, 5)), GRASS);
        assert_eq!(world.get(BlockPos::new(5, 7, 5)), W::AIR);
    }

    #[test]
    fn a_degenerate_outline_is_rejected() {
        assert!(Polygon::new(vec![(0.0, 0.0), (1.0, 1.0)]).is_none());
        assert!(
            Polygon::new(vec![(0.0, 0.0), (1.0, 1.0), (2.0, 2.0)]).is_none(),
            "collinear outlines have no area"
        );
    }
}
