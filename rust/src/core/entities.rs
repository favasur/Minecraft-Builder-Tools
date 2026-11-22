//! The Entity Tool's free-move and rotate modes.
//!
//! Ported from `EntityDragState` and `EntityRotateState`. Both are client state machines: the player
//! grabs an entity, moves the mouse, and a stream of transforms goes to the server. All the
//! arithmetic is here — the plane hit, the snap, the surface lock and the wrapped yaw accumulation —
//! so the glue only has to feed in the eye position, the look ray and the terrain height.
//!
//! The rotate mode is the interesting one: yaw is accumulated as a *delta* around the entity, with
//! each step wrapped to `(-π, π]`, so swinging the cursor past the ±180° seam turns the entity the
//! short way instead of spinning it almost all the way round.

/// How far an entity must move before the client bothers to send a packet (Java: 1e-6 squared).
pub const DEAD_ZONE_SQ: f64 = 1.0e-6;

/// Where a look ray meets a horizontal plane, or `None` when it runs parallel to it or backwards.
pub fn ray_plane(eye: [f64; 3], direction: [f64; 3], plane_y: f64) -> Option<[f64; 3]> {
    if direction[1].abs() < 1.0e-5 {
        return None;
    }
    let t = (plane_y - eye[1]) / direction[1];
    if t < 0.0 {
        return None;
    }
    Some([
        eye[0] + direction[0] * t,
        plane_y,
        eye[2] + direction[2] * t,
    ])
}

/// The horizontal angle, in radians, of the player's look ray around `target`.
pub fn ray_angle(eye: [f64; 3], direction: [f64; 3], target: [f64; 3]) -> f64 {
    let mut t = 0.0;
    if direction[1].abs() > 1.0e-5 {
        t = (target[1] - eye[1]) / direction[1];
        if t < 0.0 {
            t = 0.0;
        }
    }
    let hit = [
        eye[0] + direction[0] * t,
        eye[1] + direction[1] * t,
        eye[2] + direction[2] * t,
    ];
    (hit[2] - target[2]).atan2(hit[0] - target[0])
}

/// Snaps a coordinate to a grid of `size`, the way the drag's grid-snap setting does.
pub fn snap(value: f64, size: f64) -> f64 {
    if size <= 0.001 {
        value
    } else {
        (value / size + 0.5).floor() * size
    }
}

/// The free-move drag: the entity follows the cursor along the horizontal plane it started on.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Drag {
    plane_y: f64,
    last_sent: Option<[f64; 3]>,
    active: bool,
}

impl Drag {
    /// Grabs an entity at its position — or, for an off-grid block, its model centre, which is the
    /// point the server stores and the client draws.
    pub fn start(origin: [f64; 3]) -> Self {
        Self {
            plane_y: origin[1],
            last_sent: Some(origin),
            active: true,
        }
    }

    pub fn stop(&mut self) {
        self.active = false;
        self.last_sent = None;
    }

    pub fn is_dragging(&self) -> bool {
        self.active
    }

    /// The next position to send, or `None` when the ray misses or the entity has not moved enough.
    ///
    /// `snap_size` is the grid-snap setting (`<= 0` off), `surface_lock` keeps the entity one block
    /// above the ground under the cursor, and `ground` gives the motion-blocking height at `(x, z)`.
    pub fn update(
        &mut self,
        eye: [f64; 3],
        direction: [f64; 3],
        snap_size: f64,
        surface_lock: bool,
        ground: impl Fn(f64, f64) -> i32,
    ) -> Option<[f64; 3]> {
        if !self.active {
            return None;
        }
        let hit = ray_plane(eye, direction, self.plane_y)?;
        let mut x = hit[0];
        let mut y = self.plane_y;
        let mut z = hit[2];

        if snap_size > 0.001 {
            x = snap(x, snap_size);
            z = snap(z, snap_size);
        }
        if surface_lock {
            y = ground(x, z) as f64 + 1.0;
        } else if snap_size > 0.001 {
            y = snap(y, snap_size);
        }

        let next = [x, y, z];
        if let Some(last) = self.last_sent {
            let distance = (next[0] - last[0]).powi(2)
                + (next[1] - last[1]).powi(2)
                + (next[2] - last[2]).powi(2);
            if distance < DEAD_ZONE_SQ {
                return None;
            }
        }
        self.last_sent = Some(next);
        Some(next)
    }
}

/// The rotate mode: the entity's yaw follows the cursor around it, in wrapped steps.
#[derive(Clone, Debug, PartialEq)]
pub struct Rotate {
    /// The yaw the entity had when the mode started (or its head yaw, in head mode).
    base_yaw: f64,
    /// Accumulated, unwrapped turn since the start, in radians.
    delta: f64,
    last_angle: f64,
    head: bool,
    active: bool,
}

impl Rotate {
    /// Enters rotate mode around `target`. `head` turns only the head; `base_yaw` is the body yaw,
    /// or the head yaw when `head` is set.
    pub fn start(base_yaw: f64, head: bool, eye: [f64; 3], direction: [f64; 3], target: [f64; 3]) -> Self {
        Self {
            base_yaw,
            delta: 0.0,
            last_angle: ray_angle(eye, direction, target),
            head,
            active: true,
        }
    }

    pub fn stop(&mut self) {
        self.active = false;
        self.delta = 0.0;
    }

    pub fn is_active(&self) -> bool {
        self.active
    }

    pub fn is_head_mode(&self) -> bool {
        self.head
    }

    /// How far the entity has turned since the mode started, in degrees.
    pub fn delta_degrees(&self) -> f64 {
        self.delta.to_degrees()
    }

    /// The yaw to send, in degrees.
    pub fn update(&mut self, eye: [f64; 3], direction: [f64; 3], target: [f64; 3]) -> f64 {
        let angle = ray_angle(eye, direction, target);
        let step = angle - self.last_angle;
        // Wrap the step to (-π, π] so crossing the seam does not snap the entity round.
        self.delta += step.sin().atan2(step.cos());
        self.last_angle = angle;
        self.base_yaw + self.delta.to_degrees()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const EYE: [f64; 3] = [0.0, 70.0, 0.0];

    #[test]
    fn a_look_ray_meets_the_drag_plane_where_it_crosses_it() {
        // Looking straight down from 70 at a plane at 65.
        let hit = ray_plane(EYE, [0.0, -1.0, 0.0], 65.0).unwrap();
        assert!((hit[1] - 65.0).abs() < 1.0e-9);

        // Looking forty-five degrees down and forward crosses five blocks ahead.
        let dir = [0.0, -std::f64::consts::FRAC_1_SQRT_2, std::f64::consts::FRAC_1_SQRT_2];
        let hit = ray_plane(EYE, dir, 65.0).unwrap();
        assert!((hit[2] - 5.0).abs() < 1.0e-9);

        // A horizontal ray never meets it, and a plane behind the player is refused.
        assert!(ray_plane(EYE, [1.0, 0.0, 0.0], 65.0).is_none());
        assert!(ray_plane(EYE, [0.0, 1.0, 0.0], 65.0).is_none());
    }

    #[test]
    fn the_drag_follows_the_cursor_and_refuses_tiny_moves() {
        let mut drag = Drag::start([2.0, 65.0, 3.0]);
        assert!(drag.is_dragging());

        // Straight down from above its own position: the entity stays put, so nothing is sent.
        assert_eq!(
            drag.update([2.0, 70.0, 3.0], [0.0, -1.0, 0.0], 0.0, false, |_, _| 0),
            None
        );

        // Angled: it moves, and the height is the plane it started on.
        let dir = [0.0, -std::f64::consts::FRAC_1_SQRT_2, std::f64::consts::FRAC_1_SQRT_2];
        let next = drag.update(EYE, dir, 0.0, false, |_, _| 0).unwrap();
        assert!((next[2] - 5.0).abs() < 1.0e-9);
        assert!((next[1] - 65.0).abs() < 1.0e-9);

        // The same position again is inside the dead zone.
        assert_eq!(drag.update(EYE, dir, 0.0, false, |_, _| 0), None);

        drag.stop();
        assert!(!drag.is_dragging());
        assert_eq!(drag.update(EYE, dir, 0.0, false, |_, _| 0), None);
    }

    #[test]
    fn grid_snap_rounds_the_horizontal_position_and_the_height() {
        assert_eq!(snap(1.2, 1.0), 1.0);
        assert_eq!(snap(1.6, 1.0), 2.0);
        assert_eq!(snap(1.2, 0.0), 1.2, "a zero grid leaves the value alone");

        let mut drag = Drag::start([0.0, 65.0, 0.0]);
        let dir = [0.0, -std::f64::consts::FRAC_1_SQRT_2, std::f64::consts::FRAC_1_SQRT_2];
        let next = drag.update(EYE, dir, 1.0, false, |_, _| 0).unwrap();
        assert_eq!(next[0].fract(), 0.0, "snapped to whole blocks");
        assert_eq!(next[2], 5.0);
    }

    #[test]
    fn surface_lock_puts_the_entity_on_top_of_the_ground() {
        let mut drag = Drag::start([0.0, 65.0, 0.0]);
        let dir = [0.0, -1.0, 0.0];
        // Ground of 62 under the cursor: the entity sits at 63.
        let next = drag.update(EYE, dir, 0.0, true, |_, _| 62).unwrap();
        assert!((next[1] - 63.0).abs() < 1.0e-9);
        // And it does not move again while the ground stays the same.
        assert_eq!(drag.update(EYE, dir, 0.0, true, |_, _| 62), None);
        // A step of ground moves it a block.
        let next = drag.update(EYE, dir, 0.0, true, |_, _| 63).unwrap();
        assert!((next[1] - 64.0).abs() < 1.0e-9);
    }

    #[test]
    fn rotate_accumulates_and_wraps_without_snapping() {
        let target = [0.0, 65.0, 0.0];
        // Standing five south of the target and looking north: the cursor is at +90 degrees round
        // the entity. Moving to five east and looking west puts it at 0.
        let mut rotate = Rotate::start(0.0, false, [0.0, 65.0, 5.0], [0.0, 0.0, -1.0], target);
        assert!(rotate.is_active());
        assert!((rotate.delta_degrees() - 0.0).abs() < 1.0e-9);

        let yaw = rotate.update([5.0, 65.0, 0.0], [-1.0, 0.0, 0.0], target);
        assert!((yaw + 90.0).abs() < 1.0e-6, "got {yaw}");

        // Crossing the seam: either side of due north by a hundredth of a block is a small turn,
        // not a 359-degree one.
        let mut seam = Rotate::start(0.0, false, [-0.01, 65.0, -5.0], [0.0, 0.0, -1.0], target);
        let yaw = seam.update([0.01, 65.0, -5.0], [0.0, 0.0, -1.0], target);
        assert!(yaw.abs() < 2.0, "the seam must not spin the entity: got {yaw}");
    }

    #[test]
    fn head_mode_is_reported_and_stops_cleanly() {
        let mut rotate = Rotate::start(45.0, true, EYE, [0.0, -1.0, 0.0], [0.0, 65.0, 0.0]);
        assert!(rotate.is_head_mode());
        assert!((rotate.update(EYE, [0.0, -1.0, 0.0], [0.0, 65.0, 0.0]) - 45.0).abs() < 1.0e-9);
        rotate.stop();
        assert!(!rotate.is_active());
        assert_eq!(rotate.update(EYE, [1.0, 0.0, 0.0], [0.0, 65.0, 0.0]), 45.0);
    }
}
