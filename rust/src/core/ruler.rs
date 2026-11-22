//! The Ruler and Laser tools' state.
//!
//! Ported from `RulerState` and `LaserState`. The ruler keeps the two points a player measured and
//! turns them into the numbers the action bar shows: the per-axis spans, the true diagonal, the
//! volume and the midpoint. The laser keeps the last block its beam hit so the reading only updates
//! when the target actually changes, instead of every frame.

use super::pos::BlockPos;

/// The two measured points.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Ruler {
    pub a: Option<BlockPos>,
    pub b: Option<BlockPos>,
}

/// What a finished measurement reads.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Measurement {
    pub dx: i32,
    pub dy: i32,
    pub dz: i32,
    /// The diagonal between the two points, in blocks.
    pub distance: f64,
    /// How many blocks the box holds, counting both ends.
    pub blocks: u64,
    pub midpoint: [f64; 3],
}

impl Ruler {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set_a(&mut self, pos: BlockPos) {
        self.a = Some(pos);
    }

    pub fn set_b(&mut self, pos: BlockPos) {
        self.b = Some(pos);
    }

    pub fn clear(&mut self) {
        self.a = None;
        self.b = None;
    }

    pub fn has_measurement(&self) -> bool {
        self.a.is_some() && self.b.is_some()
    }

    /// The measurement once both points are set.
    pub fn measure(&self) -> Option<Measurement> {
        let (a, b) = (self.a?, self.b?);
        let dx = b.x - a.x;
        let dy = b.y - a.y;
        let dz = b.z - a.z;
        let distance = ((dx * dx + dy * dy + dz * dz) as f64).sqrt();
        let blocks = (dx.unsigned_abs() as u64 + 1)
            * (dy.unsigned_abs() as u64 + 1)
            * (dz.unsigned_abs() as u64 + 1);
        Some(Measurement {
            dx,
            dy,
            dz,
            distance,
            blocks,
            midpoint: [
                (a.x as f64 + b.x as f64) / 2.0 + 0.5,
                (a.y as f64 + b.y as f64) / 2.0 + 0.5,
                (a.z as f64 + b.z as f64) / 2.0 + 0.5,
            ],
        })
    }
}

/// The laser's last hit, so the action bar is only rewritten when it changes.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Laser {
    pub target: Option<BlockPos>,
    pub distance: f64,
}

impl Laser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Records a hit. `false` when the target and distance are unchanged.
    pub fn update(&mut self, target: BlockPos, distance: f64) -> bool {
        if self.target == Some(target) && (self.distance - distance).abs() < 1.0e-9 {
            return false;
        }
        self.target = Some(target);
        self.distance = distance;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pos(x: i32, y: i32, z: i32) -> BlockPos {
        BlockPos::new(x, y, z)
    }

    #[test]
    fn the_ruler_reads_the_spans_the_diagonal_and_the_volume() {
        let mut ruler = Ruler::new();
        assert!(!ruler.has_measurement());
        assert!(ruler.measure().is_none());

        ruler.set_a(pos(0, 64, 0));
        ruler.set_b(pos(3, 68, 4));
        assert!(ruler.has_measurement());
        let measurement = ruler.measure().unwrap();
        assert_eq!((measurement.dx, measurement.dy, measurement.dz), (3, 4, 4));
        assert!((measurement.distance - 41.0_f64.sqrt()).abs() < 1.0e-9);
        assert_eq!(measurement.blocks, 4 * 5 * 5);
        assert_eq!(measurement.midpoint, [2.0, 66.5, 2.5]);
    }

    #[test]
    fn a_measurement_works_backwards_too() {
        let mut ruler = Ruler::new();
        ruler.set_a(pos(10, 70, 10));
        ruler.set_b(pos(0, 60, 0));
        let measurement = ruler.measure().unwrap();
        assert_eq!((measurement.dx, measurement.dy, measurement.dz), (-10, -10, -10));
        assert_eq!(measurement.blocks, 11 * 11 * 11);
        assert_eq!(measurement.midpoint, [5.5, 65.5, 5.5]);
    }

    #[test]
    fn clearing_the_ruler_forgets_both_points() {
        let mut ruler = Ruler::new();
        ruler.set_a(pos(1, 1, 1));
        ruler.set_b(pos(2, 2, 2));
        ruler.clear();
        assert!(!ruler.has_measurement());
    }

    #[test]
    fn the_laser_only_reports_a_change() {
        let mut laser = Laser::new();
        assert!(laser.update(pos(1, 2, 3), 4.0));
        assert!(!laser.update(pos(1, 2, 3), 4.0), "same target, same distance");
        assert!(laser.update(pos(1, 2, 4), 4.0), "a new block");
        assert!(laser.update(pos(1, 2, 4), 5.0), "same block, new distance");
        assert_eq!(laser.target, Some(pos(1, 2, 4)));
    }
}
