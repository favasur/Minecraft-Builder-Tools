//! Surveyor stakes: the anchors a builder plants to fix an angle or an A–B span.
//!
//! A stake is one block the builder places where they want a corner. Everything else is derived
//! from the planted order, never stored twice:
//!
//! * two stakes give a span — its length and its bearing;
//! * three stakes give an angle at the middle one, with the turn direction;
//! * two stakes in the same column give a vertical extent, which is exactly what a claim's
//!   `startY`/`endY` needs;
//! * every stake in order gives the control points of a linear object, or the vertices of a
//!   territory.
//!
//! Keeping stakes as plain block positions means the same hooks serve both tools and the block
//! itself carries no state that a world save would have to preserve.

use super::arch::Vec3;
use super::polygon::Polygon;
use super::pos::BlockPos;

/// How many stakes a build may keep. Angular work needs three, a polygon needs more.
pub const MAX_STAKES: usize = 16;

/// What a stake was planted for.
///
/// The stake block is the same in every case; the tool in the builder's hand decides the kind, and
/// the kind is what tells the survey which object the stakes describe. A survey whose stakes
/// disagree is not an error — it simply describes nothing until they agree, so a builder who
/// switches tools mid-outline is told to finish one object rather than being handed a mixed one.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum StakeKind {
    /// Control points of a linear object: a path, a lane, an arch.
    Linear,
    /// The vertices of a territory on the ground.
    Territory,
    /// The vertices of a wall polygon: a territory raised into walls.
    Housing,
}

/// One planted stake.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Stake {
    pub pos: BlockPos,
    /// What the sidebar shows: `A`, `B`, `C`, … for the first 26.
    pub label: String,
    /// The tool that planted it.
    pub kind: StakeKind,
}

/// A span between two stakes.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Span {
    pub from: BlockPos,
    pub to: BlockPos,
    pub length: f64,
    /// Compass bearing in degrees: 0 is north (`-Z`), 90 is east (`+X`), matching the game's yaw.
    pub bearing: f64,
}

/// Which way three stakes turn.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Turn {
    Left,
    Straight,
    Right,
}

/// The angle at the middle of three stakes.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Angle {
    pub vertex: BlockPos,
    /// The angle between the two arms, in degrees, 0–180.
    pub degrees: f64,
    pub turn: Turn,
}

/// The planted stakes, in planting order.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Survey {
    stakes: Vec<Stake>,
}

impl Survey {
    pub fn new() -> Self {
        Self::default()
    }

    /// Plants a stake for a territory survey.
    ///
    /// Returns `None` once the survey is full — the builder is told, rather than an earlier stake
    /// silently disappearing from their polygon.
    pub fn plant(&mut self, pos: BlockPos) -> Option<&Stake> {
        self.plant_as(pos, StakeKind::Territory)
    }

    /// Plants a stake of a given kind: a housing stake plants walls, a linear one a path.
    pub fn plant_as(&mut self, pos: BlockPos, kind: StakeKind) -> Option<&Stake> {
        if self.stakes.len() >= MAX_STAKES {
            return None;
        }
        let label = label_for(self.stakes.len());
        self.stakes.push(Stake { pos, label, kind });
        self.stakes.last()
    }

    /// Removes the most recently planted stake.
    pub fn unplant(&mut self) -> Option<Stake> {
        self.stakes.pop()
    }

    pub fn clear(&mut self) {
        self.stakes.clear();
    }

    pub fn stakes(&self) -> &[Stake] {
        &self.stakes
    }

    pub fn is_empty(&self) -> bool {
        self.stakes.is_empty()
    }

    pub fn len(&self) -> usize {
        self.stakes.len()
    }

    /// The kind every stake agrees on, or `None` when the survey is empty or mixes kinds.
    pub fn kind(&self) -> Option<StakeKind> {
        let first = self.stakes.first()?.kind;
        self.stakes
            .iter()
            .all(|stake| stake.kind == first)
            .then_some(first)
    }

    /// The last two stakes as a span.
    pub fn span(&self) -> Option<Span> {
        let n = self.stakes.len();
        if n < 2 {
            return None;
        }
        let from = self.stakes[n - 2].pos;
        let to = self.stakes[n - 1].pos;
        let (dx, dz) = (
            (to.x - from.x) as f64,
            (to.z - from.z) as f64,
        );
        let length = ((dx * dx + dz * dz) as f64).sqrt();
        let bearing = if length < 1.0e-9 {
            0.0
        } else {
            // 0 = north (-Z), increasing clockwise, as the game's yaw does.
            let deg = dx.atan2(-dz).to_degrees();
            if deg < 0.0 { deg + 360.0 } else { deg }
        };
        Some(Span {
            from,
            to,
            length,
            bearing,
        })
    }

    /// The angle at the middle of the last three stakes.
    pub fn angle(&self) -> Option<Angle> {
        let n = self.stakes.len();
        if n < 3 {
            return None;
        }
        let a = self.stakes[n - 3].pos;
        let vertex = self.stakes[n - 2].pos;
        let c = self.stakes[n - 1].pos;

        let u = ((a.x - vertex.x) as f64, (a.z - vertex.z) as f64);
        let v = ((c.x - vertex.x) as f64, (c.z - vertex.z) as f64);
        let (lu, lv) = (
            (u.0 * u.0 + u.1 * u.1).sqrt(),
            (v.0 * v.0 + v.1 * v.1).sqrt(),
        );
        if lu < 1.0e-9 || lv < 1.0e-9 {
            return None;
        }
        let cos = ((u.0 * v.0 + u.1 * v.1) / (lu * lv)).clamp(-1.0, 1.0);
        let degrees = cos.acos().to_degrees();
        // In this right-handed XZ plane (X east, Z south) a positive cross product is a left turn.
        let cross = u.0 * v.1 - u.1 * v.0;
        let turn = if cross > 1.0e-6 {
            Turn::Left
        } else if cross < -1.0e-6 {
            Turn::Right
        } else {
            Turn::Straight
        };
        Some(Angle {
            vertex,
            degrees,
            turn,
        })
    }

    /// The vertical extent spanned by the last two stakes when they share a column — the way a
    /// builder sets a claim's `startY`/`endY` with two stakes.
    pub fn vertical_extent(&self) -> Option<(i32, i32)> {
        let n = self.stakes.len();
        if n < 2 {
            return None;
        }
        let a = self.stakes[n - 2].pos;
        let b = self.stakes[n - 1].pos;
        if a.x != b.x || a.z != b.z {
            return None;
        }
        Some((a.y.min(b.y), a.y.max(b.y)))
    }

    /// The stakes as control points for a linear object, centred in their blocks so a lane lines up
    /// with the block the builder clicked.
    pub fn path_points(&self) -> Vec<Vec3> {
        self.stakes
            .iter()
            .map(|stake| {
                Vec3::new(
                    stake.pos.x as f64 + 0.5,
                    stake.pos.y as f64,
                    stake.pos.z as f64 + 0.5,
                )
            })
            .collect()
    }

    /// The stakes as a territory outline, through the centres of their blocks.
    pub fn polygon(&self) -> Option<Polygon> {
        let points: Vec<(f64, f64)> = self
            .stakes
            .iter()
            .map(|stake| (stake.pos.x as f64 + 0.5, stake.pos.z as f64 + 0.5))
            .collect();
        Polygon::new(points)
    }
}

fn label_for(index: usize) -> String {
    if index < 26 {
        ((b'A' + index as u8) as char).to_string()
    } else {
        format!("{}", index + 1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(x: i32, y: i32, z: i32) -> BlockPos {
        BlockPos::new(x, y, z)
    }

    #[test]
    fn two_stakes_give_a_span_and_its_bearing() {
        let mut survey = Survey::new();
        survey.plant(at(0, 64, 0));
        assert!(survey.span().is_none(), "a single stake is not a span");

        survey.plant(at(10, 64, 0));
        let span = survey.span().unwrap();
        assert_eq!(span.length, 10.0);
        // East is +X, which is bearing 90.
        assert!((span.bearing - 90.0).abs() < 1.0e-9, "bearing {}", span.bearing);

        survey.plant(at(10, 64, -10));
        let span = survey.span().unwrap();
        assert!((span.bearing - 0.0).abs() < 1.0e-9, "north is -Z, got {}", span.bearing);
        assert!((span.length - 10.0).abs() < 1.0e-9);
    }

    #[test]
    fn three_stakes_give_the_angle_at_the_middle_one() {
        let mut survey = Survey::new();
        survey.plant(at(10, 64, 0)); // arm A
        survey.plant(at(0, 64, 0)); // vertex B
        survey.plant(at(0, 64, 10)); // arm C

        let angle = survey.angle().unwrap();
        assert_eq!(angle.vertex, at(0, 64, 0));
        assert!((angle.degrees - 90.0).abs() < 1.0e-9, "got {}", angle.degrees);
        assert_eq!(angle.turn, Turn::Left);
    }

    #[test]
    fn a_straight_run_and_a_reversal_are_both_reported() {
        let mut survey = Survey::new();
        survey.plant(at(0, 64, 0));
        survey.plant(at(5, 64, 0));
        survey.plant(at(10, 64, 0));
        let angle = survey.angle().unwrap();
        assert!((angle.degrees - 180.0).abs() < 1.0e-9, "got {}", angle.degrees);
        assert_eq!(angle.turn, Turn::Straight);

        let mut survey = Survey::new();
        survey.plant(at(10, 64, 0));
        survey.plant(at(5, 64, 0));
        survey.plant(at(10, 64, 0));
        let angle = survey.angle().unwrap();
        assert!(angle.degrees < 1.0e-9, "a reversal is 0 degrees, got {}", angle.degrees);
    }

    #[test]
    fn the_turn_direction_follows_the_side_swung_to() {
        let mut left = Survey::new();
        left.plant(at(10, 64, 0));
        left.plant(at(0, 64, 0));
        left.plant(at(0, 64, 10));
        assert_eq!(left.angle().unwrap().turn, Turn::Left);

        let mut right = Survey::new();
        right.plant(at(0, 64, 10));
        right.plant(at(0, 64, 0));
        right.plant(at(10, 64, 0));
        assert_eq!(right.angle().unwrap().turn, Turn::Right);
    }

    #[test]
    fn two_stakes_in_one_column_set_the_vertical_extent() {
        let mut survey = Survey::new();
        survey.plant(at(3, 64, 3));
        survey.plant(at(3, -20, 3));
        assert_eq!(survey.vertical_extent(), Some((-20, 64)));

        // Stakes in different columns describe an outline, not a height.
        survey.plant(at(9, 64, 9));
        assert_eq!(survey.vertical_extent(), None);
        // Two stake piles stacked in one column are the height entry.
        survey.plant(at(9, 70, 9));
        assert_eq!(survey.vertical_extent(), Some((64, 70)));
    }

    #[test]
    fn the_stakes_become_path_points_and_a_polygon() {
        let mut survey = Survey::new();
        for (x, z) in [(0, 0), (8, 0), (8, 8)] {
            survey.plant(at(x, 64, z));
        }

        let points = survey.path_points();
        assert_eq!(points.len(), 3);
        assert_eq!(points[0], Vec3::new(0.5, 64.0, 0.5));
        assert_eq!(points[2], Vec3::new(8.5, 64.0, 8.5));

        // The outline is the half-square on the x > z side of the diagonal.
        let polygon = survey.polygon().unwrap();
        assert!((polygon.area().abs() - 32.0).abs() < 1.0e-9);
        assert!(polygon.contains(7.0, 2.0));
        assert!(!polygon.contains(2.0, 7.0));
    }

    #[test]
    fn a_survey_needs_three_stakes_for_a_polygon_and_labels_in_order() {
        let mut survey = Survey::new();
        survey.plant(at(0, 64, 0));
        survey.plant(at(4, 64, 0));
        assert!(survey.polygon().is_none(), "two stakes cannot enclose anything");
        survey.plant(at(4, 64, 4));
        assert!(survey.polygon().is_some());

        let labels: Vec<&str> = survey.stakes().iter().map(|s| s.label.as_str()).collect();
        assert_eq!(labels, vec!["A", "B", "C"]);
    }

    #[test]
    fn unplanting_removes_the_last_stake_and_full_surveys_refuse_more() {
        let mut survey = Survey::new();
        survey.plant(at(0, 64, 0));
        survey.plant(at(4, 64, 0));
        assert_eq!(survey.unplant().unwrap().pos, at(4, 64, 0));
        assert_eq!(survey.len(), 1);

        let mut survey = Survey::new();
        for i in 0..MAX_STAKES {
            assert!(survey.plant(at(i as i32, 64, 0)).is_some());
        }
        assert!(survey.plant(at(99, 64, 0)).is_none());
        assert_eq!(survey.len(), MAX_STAKES);
    }
}
