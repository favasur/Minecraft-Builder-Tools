//! Wall polygons: a closed outline raised into a wall of a given height.
//!
//! Narodnaya Karta's wall polygon is a territory you build upwards: the stakes give the outline,
//! the right-side window gives the height, and Enter erects the wall. The wall follows every edge
//! of the outline, so a triangle becomes a triangular wall, a hexagon a hexagonal one, and a
//! concave outline its concave shape — no special cases per shape.
//!
//! Two properties the shape gets for free from the maths:
//!
//! * **Corners are mitred once.** A cell is wall when its centre is inside the polygon and within
//!   the course's width *of the nearest edge*, so two edges meeting at a corner claim a cell once,
//!   never twice, and the corner fills the way an open-angle band would not.
//! * **The wall is a wedge.** Each course is `batter` thinner than the one below it, measured from
//!   the outline, which is the same tapered cross-section the arch and wedge tools build. The wall
//!   stops when a course would be thinner than [`MIN_COURSE`], so a strongly battered wall closes
//!   into a ridge rather than to a sliver.
//!
//! A stake sits at a block's centre, so a housing outline runs through cell centres. A course's
//! width is measured to cell centres, exactly as [`super::polygon::Border::width`] is, so a 1-block
//! course is two cells deep on the side where the outline passes through the cells it splits.

use super::edit::BlockEdit;
use super::ops::{BlockChange, MAX_BLOCKS};
use super::polygon::Polygon;
use super::pos::BlockPos;
use super::preview::{self, Preview, PreviewStyle};
use super::survey::{Stake, StakeKind, Survey};
use super::world::{BlockId, World};

/// The thinnest course a battered wall keeps. A course thinner than this is not built, which is
/// how a steep batter closes the wall into a ridge.
pub const MIN_COURSE: f64 = 0.5;

/// What a wall polygon is made of.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WallSpec {
    pub block: BlockId,
    /// Courses to erect, counted upwards from the base. The sidebar's height field.
    pub height: u32,
    /// Band width at the base, in blocks — the same unit as [`super::polygon::Border::width`].
    pub thickness: f64,
    /// How much thinner each course is than the one below it. Zero gives a straight wall.
    pub batter: f64,
    /// The course that closes the top, when it is not `block`.
    pub cap: Option<BlockId>,
}

impl WallSpec {
    /// A straight wall of one block's thickness: the plain case.
    pub const fn new(block: BlockId, height: u32) -> Self {
        Self {
            block,
            height,
            thickness: 1.0,
            batter: 0.0,
            cap: None,
        }
    }

    /// A battered wall: `thickness` at the base, narrowing by `batter` per course.
    pub const fn battered(block: BlockId, height: u32, thickness: f64, batter: f64) -> Self {
        Self {
            block,
            height,
            thickness,
            batter,
            cap: None,
        }
    }

    pub const fn with_cap(mut self, cap: BlockId) -> Self {
        self.cap = Some(cap);
        self
    }

    pub const fn with_height(mut self, height: u32) -> Self {
        self.height = height;
        self
    }

    pub const fn with_thickness(mut self, thickness: f64) -> Self {
        self.thickness = thickness;
        self
    }

    /// The band width of course `layer`, or `None` when the wall has tapered away before it.
    pub fn course(&self, layer: u32) -> Option<f64> {
        if layer >= self.height {
            return None;
        }
        let width = self.thickness - self.batter * layer as f64;
        if width < MIN_COURSE {
            None
        } else {
            Some(width)
        }
    }

    /// How many courses this spec actually builds, which a batter can cut short of `height`.
    pub fn courses(&self) -> u32 {
        let mut built = 0;
        while self.course(built).is_some() {
            built += 1;
        }
        built
    }

    /// The block of course `layer`: the cap on the top course, `block` elsewhere.
    pub fn block_at(&self, layer: u32) -> BlockId {
        match self.cap {
            Some(cap) if layer + 1 >= self.courses() => cap,
            _ => self.block,
        }
    }

    /// Whether the cell at `(x, z)` is in course `layer`, whose centre is `distance` from the
    /// outline.
    pub fn covers(&self, layer: u32, distance: f64) -> bool {
        matches!(self.course(layer), Some(width) if distance <= width)
    }
}

/// The cells a wall covers, bottom-up, as changes against `world` at `base_y`.
///
/// Every cell appears once: the course test is a distance to the outline, not a per-edge sweep, so
/// mitred corners and shared vertices cannot be written twice.
pub fn build<W: World>(
    world: &mut W,
    polygon: &Polygon,
    spec: &WallSpec,
    base_y: i32,
) -> Vec<BlockChange> {
    let (min_x, min_z, max_x, max_z) = polygon.bounds();
    let mut out = Vec::new();

    for x in (min_x.floor() as i32)..(max_x.ceil() as i32) {
        for z in (min_z.floor() as i32)..(max_z.ceil() as i32) {
            let (cx, cz) = (x as f64 + 0.5, z as f64 + 0.5);
            if !polygon.contains(cx, cz) {
                continue;
            }
            let distance = polygon.distance_to_edge(cx, cz);
            for layer in 0..spec.height {
                if !spec.covers(layer, distance) {
                    break;
                }
                if out.len() as u64 >= MAX_BLOCKS {
                    return out;
                }
                let pos = BlockPos::new(x, base_y + layer as i32, z);
                let before = world.get(pos);
                let after = spec.block_at(layer);
                world.set(pos, after);
                out.push(BlockChange { pos, before, after });
            }
        }
    }
    out
}

/// The wall's preview: the yellow footprint it will stand on, the outline at the base, and the same
/// outline raised to the wall's top with a post at every vertex, so the height reads at a glance.
pub fn preview(polygon: &Polygon, spec: &WallSpec, base_y: i32, style: PreviewStyle) -> Preview {
    let base = preview::plane(base_y);
    let top = preview::plane(base_y + spec.courses() as i32);
    let mut out = Preview {
        quads: preview::polygon_band(polygon, base, spec.thickness, style.fill),
        lines: preview::polygon_ring(polygon, base, style.outline, style.width),
    };
    out.lines
        .extend(preview::polygon_ring(polygon, top, style.outline, style.width));
    for (x, z) in polygon.points() {
        out.lines.push(preview::PreviewLine {
            from: [*x, base, *z],
            to: [*x, top, *z],
            color: style.outline,
            width: style.width,
            depth: preview::PreviewDepth::Tested,
        });
    }
    out
}

/// The Housing Surveyor Stake tool: plant the stakes, press Enter, the outline becomes walls.
///
/// The stakes themselves are ordinary [`Survey`] stakes tagged [`StakeKind::Housing`], so the same
/// planting, unplanting and labelling code serves both tools and nothing has to be kept in step.
#[derive(Clone, Debug, PartialEq)]
pub struct Housing {
    survey: Survey,
    spec: WallSpec,
}

impl Housing {
    /// A housing survey whose walls are built from `spec`; the sidebar edits the spec in place.
    pub fn new(spec: WallSpec) -> Self {
        Self {
            survey: Survey::new(),
            spec,
        }
    }

    /// Plants a housing stake. `None` once the survey is full.
    pub fn plant(&mut self, pos: BlockPos) -> Option<&Stake> {
        self.survey.plant_as(pos, StakeKind::Housing)
    }

    pub fn unplant(&mut self) -> Option<Stake> {
        self.survey.unplant()
    }

    pub fn clear(&mut self) {
        self.survey.clear()
    }

    pub fn stakes(&self) -> &[Stake] {
        self.survey.stakes()
    }

    pub fn len(&self) -> usize {
        self.survey.len()
    }

    pub fn is_empty(&self) -> bool {
        self.survey.is_empty()
    }

    pub fn spec(&self) -> &WallSpec {
        &self.spec
    }

    /// The sidebar writes the spec here; the height is only read when Enter is pressed.
    pub fn spec_mut(&mut self) -> &mut WallSpec {
        &mut self.spec
    }

    /// The right-side window's height field.
    pub fn set_height(&mut self, height: u32) {
        self.spec.height = height;
    }

    /// The outline the stakes describe, once there are three of them.
    pub fn polygon(&self) -> Option<Polygon> {
        self.survey.polygon()
    }

    /// Where the wall starts: the lowest stake, so a survey on a shelf builds on the shelf.
    pub fn base_y(&self) -> i32 {
        self.survey
            .stakes()
            .iter()
            .map(|stake| stake.pos.y)
            .min()
            .unwrap_or(0)
    }

    /// The preview while the stakes are being placed: yellow area, bright outline, raised to the
    /// height the sidebar currently holds.
    pub fn preview(&self) -> Preview {
        match self.polygon() {
            Some(polygon) => preview(&polygon, &self.spec, self.base_y(), PreviewStyle::YELLOW),
            None => Preview::new(),
        }
    }

    /// The Enter key: erects the wall and returns the changes with their packed bulk edit, or
    /// `None` when the stakes do not enclose an area yet.
    pub fn enter<W: World>(&self, world: &mut W) -> Option<(Vec<BlockChange>, BlockEdit)> {
        let polygon = self.polygon()?;
        if !self.survey.kind().is_some_and(|kind| kind == StakeKind::Housing) {
            return None;
        }
        let changes = build(world, &polygon, &self.spec, self.base_y());
        let edit = BlockEdit::pack(&changes);
        Some((changes, edit))
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

    fn square(size: f64) -> Polygon {
        Polygon::new(vec![
            (0.0, 0.0),
            (size, 0.0),
            (size, size),
            (0.0, size),
        ])
        .unwrap()
    }

    fn triangle() -> Polygon {
        Polygon::new(vec![(0.0, 0.0), (10.0, 0.0), (0.0, 10.0)]).unwrap()
    }

    #[test]
    fn a_straight_wall_follows_the_outline_one_course_per_layer() {
        let mut world = W::default();
        let spec = WallSpec::new(STONE, 3);
        let changes = build(&mut world, &square(10.0), &spec, 64);

        // The 10x10 ring is 36 cells, three courses high.
        assert_eq!(changes.len(), 108);
        assert!(changes.iter().all(|c| c.after == STONE));
        assert!(changes.iter().all(|c| (64..67).contains(&c.pos.y)));

        // The interior stays empty and the ring is solid from 64 to 66.
        assert_eq!(world.get(BlockPos::new(5, 64, 5)), W::AIR);
        assert_eq!(world.get(BlockPos::new(0, 66, 5)), STONE);
        assert_eq!(world.get(BlockPos::new(9, 65, 9)), STONE);
    }

    #[test]
    fn a_triangle_becomes_a_triangular_wall() {
        let mut world = W::default();
        let changes = build(&mut world, &triangle(), &WallSpec::new(STONE, 1), 64);

        // Every written cell is inside the triangle.
        assert!(changes.iter().all(|c| c.pos.y == 64));
        let polygon = triangle();
        assert!(
            changes
                .iter()
                .all(|c| polygon.contains_cell(c.pos.x, c.pos.z)),
            "a wall may not leave its outline"
        );
        // The two legs are built, the hypotenuse is where the outline runs.
        for (x, z) in [(0, 0), (0, 8), (5, 0), (0, 5)] {
            assert_eq!(world.get(BlockPos::new(x, 64, z)), STONE, "leg cell {x},{z}");
        }
        // The far corner of the bounding square is outside the triangle and stays air.
        assert_eq!(world.get(BlockPos::new(9, 64, 9)), W::AIR);
        // Each cell is written once: a mitred corner is not doubled.
        let unique: std::collections::HashSet<_> =
            changes.iter().map(|c| (c.pos.x, c.pos.y, c.pos.z)).collect();
        assert_eq!(unique.len(), changes.len());
    }

    #[test]
    fn a_battered_wall_narrows_course_by_course() {
        let spec = WallSpec::battered(STONE, 3, 2.0, 0.5);
        assert_eq!(spec.course(0), Some(2.0));
        assert_eq!(spec.course(1), Some(1.5));
        assert_eq!(spec.course(2), Some(1.0));

        let mut world = W::default();
        let changes = build(&mut world, &square(10.0), &spec, 0);
        let mut per_layer = [0usize; 3];
        for change in &changes {
            per_layer[change.pos.y as usize] += 1;
        }
        // Two rings, then two rings, then one ring: 64, 64, 36.
        assert_eq!(per_layer, [64, 64, 36]);
        assert_eq!(changes.len(), 164);
    }

    #[test]
    fn a_steep_batter_closes_the_wall_before_the_requested_height() {
        let spec = WallSpec::battered(STONE, 4, 1.0, 0.4);
        assert_eq!(spec.course(0), Some(1.0));
        assert_eq!(spec.course(1), Some(0.6));
        assert_eq!(spec.course(2), None, "0.2 is thinner than MIN_COURSE");
        assert_eq!(spec.courses(), 2);

        let mut world = W::default();
        let changes = build(&mut world, &square(10.0), &spec, 0);
        assert_eq!(changes.len(), 72, "two courses of the 36-cell ring");
        assert!(changes.iter().all(|c| c.pos.y < 2));
    }

    #[test]
    fn the_top_course_takes_the_cap() {
        let spec = WallSpec::new(STONE, 2).with_cap(DIRT);
        let mut world = W::default();
        build(&mut world, &square(6.0), &spec, 0);
        assert_eq!(world.get(BlockPos::new(0, 0, 3)), STONE);
        assert_eq!(world.get(BlockPos::new(0, 1, 3)), DIRT);
    }

    #[test]
    fn a_zero_height_wall_builds_nothing() {
        let mut world = W::default();
        assert!(build(&mut world, &square(6.0), &WallSpec::new(STONE, 0), 0).is_empty());
    }

    #[test]
    fn the_packed_wall_is_equivalent_to_the_per_cell_writes() {
        let spec = WallSpec::battered(STONE, 4, 2.0, 0.25);
        let mut direct = W::default();
        let changes = build(&mut direct, &square(12.0), &spec, 3);

        let mut packed = W::default();
        let edit = BlockEdit::pack(&changes);
        edit.apply(&mut packed);
        assert_eq!(edit.cell_count(), changes.len() as u64);

        for x in -1..14 {
            for z in -1..14 {
                for y in 2..9 {
                    let pos = BlockPos::new(x, y, z);
                    assert_eq!(direct.get(pos), packed.get(pos), "cell {pos:?}");
                }
            }
        }
    }

    #[test]
    fn the_preview_shows_the_footprint_and_the_top_of_the_wall() {
        let spec = WallSpec::new(STONE, 4);
        let preview = preview(&square(10.0), &spec, 64, PreviewStyle::YELLOW);

        assert!(preview.fits_budget());
        // 10 base rows of footprint band, 8 outline lines (base + top), 4 corner posts.
        assert_eq!(preview.lines.len(), 4 + 4 + 4);
        assert!(preview.quads.iter().all(|q| q.color == preview::FILL_YELLOW));
        assert!(preview.lines.iter().all(|l| l.color == preview::OUTLINE_YELLOW));

        // The top ring sits at the wall's top, the base ring on the ground.
        let base_plane = preview::plane(64);
        assert!(preview.lines.iter().any(|l| l.from[1] == base_plane));
        assert!(preview.lines.iter().any(|l| l.from[1] == preview::plane(68)));
        // A post at each vertex spans the wall's full height.
        let post = preview
            .lines
            .iter()
            .find(|l| l.from[0] == l.to[0] && l.from[2] == l.to[2])
            .unwrap();
        assert_eq!(post.to[1] - post.from[1], 4.0);
    }

    fn housing_triangle() -> Housing {
        let mut housing = Housing::new(WallSpec::new(STONE, 2));
        for (x, z) in [(0, 0), (10, 0), (0, 10)] {
            housing.plant(BlockPos::new(x, 64, z));
        }
        housing
    }

    #[test]
    fn enter_does_nothing_until_the_stakes_close_an_area() {
        let mut housing = Housing::new(WallSpec::new(STONE, 2));
        let mut world = W::default();
        housing.plant(BlockPos::new(0, 64, 0));
        assert!(housing.enter(&mut world).is_none());
        housing.plant(BlockPos::new(4, 64, 0));
        assert!(housing.enter(&mut world).is_none());
        housing.plant(BlockPos::new(4, 64, 4));
        let (changes, edit) = housing.enter(&mut world).expect("three stakes enclose");
        assert!(!changes.is_empty());
        assert_eq!(edit.cell_count(), changes.len() as u64);
    }

    #[test]
    fn enter_uses_the_height_from_the_sidebar() {
        let mut housing = Housing::new(WallSpec::new(STONE, 1));
        for (x, z) in [(0, 0), (8, 0), (8, 8), (0, 8)] {
            housing.plant(BlockPos::new(x, 64, z));
        }
        housing.set_height(5);
        assert_eq!(housing.spec().height, 5);

        let mut world = W::default();
        let (changes, _) = housing.enter(&mut world).unwrap();
        assert_eq!(housing.base_y(), 64);
        assert_eq!(changes.iter().map(|c| c.pos.y).max().unwrap(), 68);
        assert_eq!(changes.iter().map(|c| c.pos.y).min().unwrap(), 64);

        // Height is the course count: every course is the same outline.
        let mut one_course = W::default();
        let per_course = build(
            &mut one_course,
            &housing.polygon().unwrap(),
            &WallSpec::new(STONE, 1),
            64,
        )
        .len();
        assert_eq!(changes.len(), per_course * 5);
    }

    #[test]
    fn the_wall_starts_at_the_lowest_stake() {
        let mut housing = Housing::new(WallSpec::new(STONE, 1));
        for (x, y, z) in [(0, 70, 0), (8, 64, 0), (8, 66, 8)] {
            housing.plant(BlockPos::new(x, y, z));
        }
        assert_eq!(housing.base_y(), 64);

        let mut world = W::default();
        let (changes, _) = housing.enter(&mut world).unwrap();
        assert!(changes.iter().all(|c| c.pos.y == 64));
    }

    #[test]
    fn housing_stakes_are_tagged_as_housing() {
        let housing = housing_triangle();
        assert!(housing.stakes().iter().all(|s| s.kind == StakeKind::Housing));
        assert_eq!(housing.len(), 3);
        assert!(housing.preview().fits_budget());
    }
}
