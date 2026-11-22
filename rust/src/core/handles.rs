//! Dragging a selection's face handles.
//!
//! Ported from `HandleDragState`. Grabbing a face fixes a drag plane through the grab point,
//! perpendicular to the view at grab time; while the button is held, the face tracks where the mouse
//! ray crosses that plane, projected onto the face's axis and snapped to the block grid. Because the
//! plane is fixed, the face keeps following the cursor smoothly even when the player is aiming at
//! open air.
//!
//! A click that never moved the mouse still resizes the region by one block on release (the
//! click-and-drag feel the Java tool has), unless the drag was an Alt-drag stretch.

use super::pos::BlockPos;

/// The largest block coordinate a drag will produce, the game's world edge.
pub const WORLD_LIMIT: i32 = 30_000_000;

/// Which axis a face handle moves along.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Axis {
    X,
    Y,
    Z,
}

impl Axis {
    fn unit(self) -> [f64; 3] {
        match self {
            Self::X => [1.0, 0.0, 0.0],
            Self::Y => [0.0, 1.0, 0.0],
            Self::Z => [0.0, 0.0, 1.0],
        }
    }
}

/// The region after a drag step.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Region {
    pub min: BlockPos,
    pub max: BlockPos,
}

impl Region {
    pub fn new(min: BlockPos, max: BlockPos) -> Self {
        Self { min, max }
    }

    fn coordinate(&self, axis: Axis, positive: bool) -> i32 {
        let pos = if positive { self.max } else { self.min };
        match axis {
            Axis::X => pos.x,
            Axis::Y => pos.y,
            Axis::Z => pos.z,
        }
    }

}

/// One grabbed face handle.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HandleDrag {
    axis: Axis,
    positive: bool,
    plane_point: [f64; 3],
    plane_normal: [f64; 3],
    start: Region,
    moved: bool,
    stretch: bool,
}

impl HandleDrag {
    /// Grabs a face. `grab_point` is where the player clicked the handle and `view` the look
    /// direction at that moment; `start` is the selection as it was.
    pub fn start(
        axis: Axis,
        positive: bool,
        grab_point: [f64; 3],
        view: [f64; 3],
        start: Region,
    ) -> Self {
        Self::start_stretch(axis, positive, grab_point, view, start, false)
    }

    /// The same, with the Alt-drag stretch flag: a stretch never nudges on a plain click.
    pub fn start_stretch(
        axis: Axis,
        positive: bool,
        grab_point: [f64; 3],
        view: [f64; 3],
        start: Region,
        stretch: bool,
    ) -> Self {
        let length = (view[0] * view[0] + view[1] * view[1] + view[2] * view[2]).sqrt();
        let normal = if length < 1.0e-9 {
            [0.0, 0.0, 1.0]
        } else {
            [view[0] / length, view[1] / length, view[2] / length]
        };
        Self {
            axis,
            positive,
            plane_point: grab_point,
            plane_normal: normal,
            start,
            moved: false,
            stretch,
        }
    }

    pub fn axis(&self) -> Axis {
        self.axis
    }

    pub fn positive(&self) -> bool {
        self.positive
    }

    pub fn is_stretch(&self) -> bool {
        self.stretch
    }

    /// Whether the grabbed face has actually moved during this drag.
    pub fn moved(&self) -> bool {
        self.moved
    }

    pub fn original(&self) -> Region {
        self.start
    }

    /// One drag step: the region the selection should take, or `None` when the ray is parallel to
    /// the drag plane or runs backwards.
    pub fn update(&mut self, eye: [f64; 3], direction: [f64; 3], current: Region) -> Option<Region> {
        let denominator = direction[0] * self.plane_normal[0]
            + direction[1] * self.plane_normal[1]
            + direction[2] * self.plane_normal[2];
        if denominator.abs() < 1.0e-5 {
            return None;
        }
        let to_plane = [
            self.plane_point[0] - eye[0],
            self.plane_point[1] - eye[1],
            self.plane_point[2] - eye[2],
        ];
        let t = (to_plane[0] * self.plane_normal[0]
            + to_plane[1] * self.plane_normal[1]
            + to_plane[2] * self.plane_normal[2])
            / denominator;
        if t < 0.0 {
            return None;
        }
        let point = [
            eye[0] + direction[0] * t,
            eye[1] + direction[1] * t,
            eye[2] + direction[2] * t,
        ];
        let unit = self.axis.unit();
        let along = point[0] * unit[0] + point[1] * unit[1] + point[2] * unit[2];
        let mut block = (along + 0.5).floor() as i64;
        block = block.clamp(-(WORLD_LIMIT as i64), WORLD_LIMIT as i64);
        let block = block as i32;

        if block != current.coordinate(self.axis, self.positive) {
            self.moved = true;
        }
        Some(self.apply(block))
    }

    /// Ends the drag. A release that never moved the face nudges it one block outward, so a plain
    /// click still resizes the region; a stretch never does.
    pub fn stop(&mut self, released: bool) -> Option<Region> {
        if released && !self.moved && !self.stretch {
            return Some(self.nudge());
        }
        None
    }

    /// The region with the grabbed face at `block`. The opposite face and the other two axes stay
    /// where they were when the drag began, as the Java tool's do — dragging past the opposite face
    /// swaps which end is the minimum, using that original face as the anchor.
    fn apply(&self, block: i32) -> Region {
        let start = self.start;
        let (anchor, other) = match self.axis {
            Axis::X => (start.min.x, start.max.x),
            Axis::Y => (start.min.y, start.max.y),
            Axis::Z => (start.min.z, start.max.z),
        };
        let (lo, hi) = if self.positive {
            (anchor.min(block), anchor.max(block))
        } else {
            (block.min(other), block.max(other))
        };
        match self.axis {
            Axis::X => Region::new(
                BlockPos::new(lo, start.min.y, start.min.z),
                BlockPos::new(hi, start.max.y, start.max.z),
            ),
            Axis::Y => Region::new(
                BlockPos::new(start.min.x, lo, start.min.z),
                BlockPos::new(start.max.x, hi, start.max.z),
            ),
            Axis::Z => Region::new(
                BlockPos::new(start.min.x, start.min.y, lo),
                BlockPos::new(start.max.x, start.max.y, hi),
            ),
        }
    }

    fn nudge(&self) -> Region {
        let delta = if self.positive { 1 } else { -1 };
        let mut region = self.start;
        let (min, max) = match self.axis {
            Axis::X => (
                BlockPos::new(self.start.min.x + if self.positive { 0 } else { delta }, self.start.min.y, self.start.min.z),
                BlockPos::new(self.start.max.x + if self.positive { delta } else { 0 }, self.start.max.y, self.start.max.z),
            ),
            Axis::Y => (
                BlockPos::new(self.start.min.x, self.start.min.y + if self.positive { 0 } else { delta }, self.start.min.z),
                BlockPos::new(self.start.max.x, self.start.max.y + if self.positive { delta } else { 0 }, self.start.max.z),
            ),
            Axis::Z => (
                BlockPos::new(self.start.min.x, self.start.min.y, self.start.min.z + if self.positive { 0 } else { delta }),
                BlockPos::new(self.start.max.x, self.start.max.y, self.start.max.z + if self.positive { delta } else { 0 }),
            ),
        };
        region.min = min;
        region.max = max;
        region
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pos(x: i32, y: i32, z: i32) -> BlockPos {
        BlockPos::new(x, y, z)
    }

    fn region() -> Region {
        Region::new(pos(0, 60, 0), pos(3, 65, 3))
    }

    #[test]
    fn dragging_the_far_x_face_extends_the_region() {
        let start = region();
        // Looking north at a plane through x = 3.5.
        let mut drag = HandleDrag::start(Axis::X, true, [3.5, 62.0, 0.0], [0.0, 0.0, -1.0], start);
        // Move the cursor so the ray meets the plane at x = 8 while still north of it.
        let eye = [8.0, 62.0, 5.0];
        let direction = [0.0, 0.0, -1.0];
        let next = drag.update(eye, direction, start).unwrap();
        assert_eq!(next.max.x, 8);
        assert_eq!(next.min.x, 0, "the near face stays");
        assert_eq!(next.max.y, 65);
        assert!(drag.moved());

        // Dragging past the near face swaps the ends around the original near face.
        let next = drag.update([-4.0, 62.0, 5.0], direction, next).unwrap();
        assert_eq!(next.min.x, -4);
        assert_eq!(next.max.x, 0, "the original near face is the anchor");
    }

    #[test]
    fn dragging_the_near_face_moves_the_minimum() {
        let start = region();
        // Looking north: the drag plane is the z = 0 face, and a cursor south of the grab point at
        // height 58 meets it at y = 58.
        let mut drag = HandleDrag::start(Axis::Y, false, [0.0, 60.0, 0.0], [0.0, 0.0, -1.0], start);
        let next = drag.update([0.0, 58.0, 5.0], [0.0, 0.0, -1.0], start).unwrap();
        assert_eq!(next.min.y, 58);
        assert_eq!(next.max.y, 65, "the far face stays");
    }

    #[test]
    fn a_ray_parallel_to_the_drag_plane_keeps_the_last_region() {
        let start = region();
        let mut drag = HandleDrag::start(Axis::X, true, [3.5, 62.0, 0.0], [0.0, 0.0, -1.0], start);
        // Looking east: parallel to a plane whose normal is north.
        assert_eq!(drag.update([0.0, 62.0, 0.0], [1.0, 0.0, 0.0], start), None);
    }

    #[test]
    fn a_click_that_never_moved_nudges_the_face_one_block() {
        let start = region();
        let mut drag = HandleDrag::start(Axis::X, true, [3.5, 62.0, 0.0], [0.0, 0.0, -1.0], start);
        let nudged = drag.stop(true).unwrap();
        assert_eq!(nudged.max.x, 4);

        let mut drag = HandleDrag::start(Axis::X, false, [0.0, 62.0, 0.0], [0.0, 0.0, -1.0], start);
        let nudged = drag.stop(true).unwrap();
        assert_eq!(nudged.min.x, -1);

        // A stretch, and a release after real movement, leave the region alone.
        let mut stretch = HandleDrag::start_stretch(Axis::X, true, [3.5, 62.0, 0.0], [0.0, 0.0, -1.0], start, true);
        assert!(stretch.is_stretch());
        assert_eq!(stretch.stop(true), None);
    }

    #[test]
    fn a_dragged_face_snaps_to_whole_blocks() {
        let start = region();
        let mut drag = HandleDrag::start(Axis::Z, true, [0.0, 62.0, 3.5], [1.0, 0.0, 0.0], start);
        // Looking east from two blocks west: the plane x = 3.5 is met at z = 6.4, and
        // floor(6.4 + 0.5) = 6.
        let next = drag.update([-2.0, 62.0, 6.4], [1.0, 0.0, 0.0], start).unwrap();
        assert_eq!(next.max.z, 6);
    }
}
