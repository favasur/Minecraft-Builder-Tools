//! Territory previews: the yellow ghost a builder sees before anything is committed.
//!
//! Every polygonal territory — a plain outline, a private claim, a wall polygon — previews the same
//! way: a semi-transparent yellow area over the ground it would cover, with a bright yellow ring on
//! its outline. The geometry here is renderer-agnostic: it is world-space quads and lines in the
//! shape the client submits (`WorldQuad`/`WorldLine`), so the glue only copies fields across and
//! the whole thing is unit-tested without the game.
//!
//! The fill is merged per row: one quad spans a whole horizontal run of covered cells, so the cost
//! is a quad per row of the outline rather than a quad per block. A 10 x 10 plot previews as 10
//! quads and a 200 x 200 one as 200, well inside the draw context's 4,096-quad budget.

use super::polygon::Polygon;

/// Fill colour, `0xAARRGGBB`: yellow at 40 % opacity.
pub const FILL_YELLOW: u32 = 0x66FF_D400;
/// Outline colour: bright yellow, fully opaque.
pub const OUTLINE_YELLOW: u32 = 0xFFFF_FF00;
/// Outline thickness in screen pixels.
pub const OUTLINE_WIDTH: f32 = 3.0;
/// How far above the top of the covered layer the preview plane sits, so it does not z-fight with
/// the block faces it lies on.
pub const PLANE_LIFT: f64 = 0.01;
/// Quads one view may draw, matching the client's default `WorldDrawContext` capacity.
pub const QUAD_BUDGET: usize = 4_096;
/// Lines one view may draw, likewise.
pub const LINE_BUDGET: usize = 4_096;

/// How a preview primitive is drawn against the world's depth.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PreviewDepth {
    /// Tested against the world, so blocks in front hide it.
    Tested,
    /// Drawn over everything, so a preview stays visible inside terrain.
    ThroughWalls,
}

/// One world-space quad, as `0xAARRGGBB` with alpha.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PreviewQuad {
    pub corners: [[f64; 3]; 4],
    pub color: u32,
    pub depth: PreviewDepth,
}

/// One world-space line, `width` pixels wide.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PreviewLine {
    pub from: [f64; 3],
    pub to: [f64; 3],
    pub color: u32,
    pub width: f32,
    pub depth: PreviewDepth,
}

/// The colours and thickness a preview is drawn with.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PreviewStyle {
    pub fill: u32,
    pub outline: u32,
    pub width: f32,
}

impl PreviewStyle {
    /// Semi-transparent yellow over bright yellow, the territory preview.
    pub const YELLOW: Self = Self {
        fill: FILL_YELLOW,
        outline: OUTLINE_YELLOW,
        width: OUTLINE_WIDTH,
    };
}

impl Default for PreviewStyle {
    fn default() -> Self {
        Self::YELLOW
    }
}

/// A batch of preview geometry for one view.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Preview {
    pub quads: Vec<PreviewQuad>,
    pub lines: Vec<PreviewLine>,
}

impl Preview {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn is_empty(&self) -> bool {
        self.quads.is_empty() && self.lines.is_empty()
    }

    /// Whether the batch fits in one view's budgets.
    pub fn fits_budget(&self) -> bool {
        self.quads.len() <= QUAD_BUDGET && self.lines.len() <= LINE_BUDGET
    }

    /// Moves another batch's geometry into this one.
    pub fn extend(&mut self, other: Preview) {
        self.quads.extend(other.quads);
        self.lines.extend(other.lines);
    }
}

/// The plane a preview draws on for a territory whose top layer is `top_y`.
pub fn plane(top_y: i32) -> f64 {
    top_y as f64 + 1.0 + PLANE_LIFT
}

/// One horizontal quad over the cell run `x0 .. x1` at `z`, on the plane `y`.
fn row_quad(x0: i32, x1: i32, z: i32, y: f64, color: u32) -> PreviewQuad {
    PreviewQuad {
        corners: [
            [x0 as f64, y, z as f64],
            [x1 as f64, y, z as f64],
            [x1 as f64, y, z as f64 + 1.0],
            [x0 as f64, y, z as f64 + 1.0],
        ],
        color,
        depth: PreviewDepth::Tested,
    }
}

/// The covered cells of a polygon at `plane`, filled with merged row quads.
///
/// `half_width` is the distance from the outline the fill reaches inwards; pass
/// [`f64::INFINITY`] for the whole area, or the wall thickness for a footprint band.
pub fn polygon_band(
    polygon: &Polygon,
    plane: f64,
    half_width: f64,
    color: u32,
) -> Vec<PreviewQuad> {
    let (min_x, min_z, max_x, max_z) = polygon.bounds();
    let mut quads = Vec::new();
    for z in (min_z.floor() as i32)..(max_z.ceil() as i32) {
        let mut run: Option<i32> = None;
        for x in (min_x.floor() as i32)..(max_x.ceil() as i32) {
            let covered = polygon.contains_cell(x, z)
                && polygon.distance_to_edge(x as f64 + 0.5, z as f64 + 0.5) <= half_width;
            match (run, covered) {
                (None, true) => run = Some(x),
                (Some(start), false) => {
                    quads.push(row_quad(start, x, z, plane, color));
                    run = None;
                }
                _ => {}
            }
        }
        if let Some(start) = run {
            quads.push(row_quad(start, max_x.ceil() as i32, z, plane, color));
        }
    }
    quads
}

/// The closed outline of a polygon at `plane`, one line per edge.
pub fn polygon_ring(
    polygon: &Polygon,
    plane: f64,
    color: u32,
    width: f32,
) -> Vec<PreviewLine> {
    let points = polygon.points();
    let n = points.len();
    (0..n)
        .map(|i| {
            let a = points[i];
            let b = points[(i + 1) % n];
            PreviewLine {
                from: [a.0, plane, a.1],
                to: [b.0, plane, b.1],
                color,
                width,
                depth: PreviewDepth::Tested,
            }
        })
        .collect()
}

/// The preview of any polygonal territory: the yellow area under the outline, ringed in bright
/// yellow.
pub fn polygon_preview(polygon: &Polygon, top_y: i32, style: PreviewStyle) -> Preview {
    let plane = plane(top_y);
    Preview {
        quads: polygon_band(polygon, plane, f64::INFINITY, style.fill),
        lines: polygon_ring(polygon, plane, style.outline, style.width),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn square(size: f64) -> Polygon {
        Polygon::new(vec![
            (0.0, 0.0),
            (size, 0.0),
            (size, size),
            (0.0, size),
        ])
        .unwrap()
    }

    /// A concave L: 6x2 plus 2x4.
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
    fn a_square_preview_is_a_yellow_area_with_a_bright_ring() {
        let preview = polygon_preview(&square(10.0), 63, PreviewStyle::YELLOW);
        assert_eq!(preview.quads.len(), 10, "one merged quad per row");
        assert_eq!(preview.lines.len(), 4, "one line per edge");
        assert!(preview.fits_budget());

        // Semi-transparent yellow fill, opaque bright yellow outline.
        assert_eq!(preview.quads[0].color, FILL_YELLOW);
        assert_eq!(preview.quads[0].color >> 24, 0x66);
        assert_eq!(preview.lines[0].color, OUTLINE_YELLOW);
        assert_eq!(preview.lines[0].color >> 24, 0xFF);

        // Each quad spans the full 10-block row, on the plane above the top layer.
        let quad = preview.quads[0];
        assert_eq!(quad.corners[0][0], 0.0);
        assert_eq!(quad.corners[1][0], 10.0);
        assert!((quad.corners[0][1] - (64.0 + PLANE_LIFT)).abs() < 1.0e-9);
        assert_eq!(quad.depth, PreviewDepth::Tested);
    }

    #[test]
    fn the_outline_closes_the_ring() {
        let preview = polygon_preview(&square(4.0), 0, PreviewStyle::YELLOW);
        let first = preview.lines[0];
        let last = preview.lines[preview.lines.len() - 1];
        assert_eq!(last.to, first.from, "the last edge must return to the first vertex");
    }

    #[test]
    fn a_band_preview_covers_only_the_border() {
        let plane = plane(0);
        let ring = polygon_band(&square(10.0), plane, 1.0, FILL_YELLOW);
        let covered: f64 = ring.iter().map(|quad| quad.corners[1][0] - quad.corners[0][0]).sum();
        assert_eq!(covered, 36.0, "a 10x10 plot's one-block ring is 36 cells");

        // The interior of each row is one run, but the two side columns are separate runs.
        assert_eq!(ring.len(), 18);
    }

    #[test]
    fn a_concave_outline_previews_the_notch_as_empty() {
        let plane = plane(0);
        let quads = polygon_band(&elbow(), plane, f64::INFINITY, FILL_YELLOW);
        let covered: f64 = quads.iter().map(|quad| quad.corners[1][0] - quad.corners[0][0]).sum();
        assert_eq!(covered, 20.0, "the elbow is 20 cells, notch excluded");
        // Rows 0-1 are full runs, rows 2-5 are the narrow arm: six quads.
        assert_eq!(quads.len(), 6);
    }

    #[test]
    fn a_large_territory_stays_inside_one_view_budget() {
        let preview = polygon_preview(&square(200.0), 100, PreviewStyle::YELLOW);
        assert!(preview.fits_budget(), "{} quads", preview.quads.len());
        assert_eq!(preview.quads.len(), 200);
    }

    #[test]
    fn batches_can_be_combined_and_an_empty_one_fits() {
        let mut total = Preview::new();
        assert!(total.is_empty());
        assert!(total.fits_budget());
        total.extend(polygon_preview(&square(4.0), 0, PreviewStyle::YELLOW));
        total.extend(polygon_preview(&square(4.0), 8, PreviewStyle::YELLOW));
        assert_eq!(total.quads.len(), 8);
        assert_eq!(total.lines.len(), 8);
    }
}
