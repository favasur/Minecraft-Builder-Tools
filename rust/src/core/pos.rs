//! Block coordinates and box regions, mirroring Minecraft's `BlockPos` / `BlockPos.betweenClosed`.

/// An integer block coordinate.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Default)]
pub struct BlockPos {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl BlockPos {
    pub const fn new(x: i32, y: i32, z: i32) -> Self {
        Self { x, y, z }
    }

    pub const fn offset(self, dx: i32, dy: i32, dz: i32) -> Self {
        Self::new(self.x + dx, self.y + dy, self.z + dz)
    }

    pub const fn add(self, other: Self) -> Self {
        Self::new(self.x + other.x, self.y + other.y, self.z + other.z)
    }

    pub const fn to_array(self) -> [i32; 3] {
        [self.x, self.y, self.z]
    }

    /// The coordinate on `axis` (0 = x, 1 = y, 2 = z).
    pub const fn coord(self, axis: usize) -> i32 {
        match axis {
            0 => self.x,
            1 => self.y,
            _ => self.z,
        }
    }

    /// A copy of this position with the `axis` coordinate replaced.
    pub const fn with_coord(self, axis: usize, value: i32) -> Self {
        match axis {
            0 => Self::new(value, self.y, self.z),
            1 => Self::new(self.x, value, self.z),
            _ => Self::new(self.x, self.y, value),
        }
    }

    /// The centre of the block, as the Java mod's `Vec3.atCenterOf`.
    pub fn center(self) -> [f64; 3] {
        [
            self.x as f64 + 0.5,
            self.y as f64 + 0.5,
            self.z as f64 + 0.5,
        ]
    }

    /// Squared distance from `point` to this block's centre.
    pub fn distance_sq_to_center(self, point: [f64; 3]) -> f64 {
        let c = self.center();
        let dx = point[0] - c[0];
        let dy = point[1] - c[1];
        let dz = point[2] - c[2];
        dx * dx + dy * dy + dz * dz
    }
}

/// An inclusive block box, always normalised so `min <= max` on every axis.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BlockBox {
    min: BlockPos,
    max: BlockPos,
}

impl BlockBox {
    /// Builds a box from two arbitrary corners (as `Math.min`/`Math.max` does in the Java mod).
    pub fn new(a: BlockPos, b: BlockPos) -> Self {
        Self {
            min: BlockPos::new(a.x.min(b.x), a.y.min(b.y), a.z.min(b.z)),
            max: BlockPos::new(a.x.max(b.x), a.y.max(b.y), a.z.max(b.z)),
        }
    }

    /// A box spanning both boxes.
    pub fn union(self, other: Self) -> Self {
        Self::new(
            BlockPos::new(
                self.min.x.min(other.min.x),
                self.min.y.min(other.min.y),
                self.min.z.min(other.min.z),
            ),
            BlockPos::new(
                self.max.x.max(other.max.x),
                self.max.y.max(other.max.y),
                self.max.z.max(other.max.z),
            ),
        )
    }

    pub const fn min(self) -> BlockPos {
        self.min
    }

    pub const fn max(self) -> BlockPos {
        self.max
    }

    /// Size on each axis, in blocks (always >= 1).
    pub const fn size(self) -> [i32; 3] {
        [
            self.max.x - self.min.x + 1,
            self.max.y - self.min.y + 1,
            self.max.z - self.min.z + 1,
        ]
    }

    /// The number of cells, as `Region.volume()` in the Java mod.
    pub fn volume(self) -> u64 {
        let s = self.size();
        s[0] as u64 * s[1] as u64 * s[2] as u64
    }

    pub fn contains(self, pos: BlockPos) -> bool {
        pos.x >= self.min.x
            && pos.x <= self.max.x
            && pos.y >= self.min.y
            && pos.y <= self.max.y
            && pos.z >= self.min.z
            && pos.z <= self.max.z
    }

    /// Every cell, in x, then y, then z order (like `BlockPos.betweenClosed`).
    pub fn iter(self) -> impl Iterator<Item = BlockPos> {
        (self.min.y..=self.max.y).flat_map(move |y| {
            (self.min.z..=self.max.z).flat_map(move |z| {
                (self.min.x..=self.max.x).map(move |x| BlockPos::new(x, y, z))
            })
        })
    }

    /// The four vertical sides (`/walls`).
    pub fn is_wall(self, pos: BlockPos) -> bool {
        pos.x == self.min.x || pos.x == self.max.x || pos.z == self.min.z || pos.z == self.max.z
    }

    /// The whole shell: any axis on a boundary (`/outline` and `/hollow` in the Java mod).
    pub fn is_boundary(self, pos: BlockPos) -> bool {
        pos.x == self.min.x
            || pos.x == self.max.x
            || pos.y == self.min.y
            || pos.y == self.max.y
            || pos.z == self.min.z
            || pos.z == self.max.z
    }

    /// The top and bottom faces (`/faces`).
    pub fn is_horizontal_face(self, pos: BlockPos) -> bool {
        pos.y == self.min.y || pos.y == self.max.y
    }

    /// The 1-2 block centre of the region (`/center`).
    pub fn center_box(self) -> Self {
        let cx1 = (self.min.x + self.max.x) / 2;
        let cx2 = cx1 + if (self.max.x - self.min.x) % 2 == 0 { 0 } else { 1 };
        let cy1 = (self.min.y + self.max.y) / 2;
        let cy2 = cy1 + if (self.max.y - self.min.y) % 2 == 0 { 0 } else { 1 };
        let cz1 = (self.min.z + self.max.z) / 2;
        let cz2 = cz1 + if (self.max.z - self.min.z) % 2 == 0 { 0 } else { 1 };
        Self::new(BlockPos::new(cx1, cy1, cz1), BlockPos::new(cx2, cy2, cz2))
    }

    /// Grows the region one face along `dir` (`/expand`); the opposite face stays put.
    pub fn expand(self, dir: BlockPos, amount: i32) -> Self {
        let mut min = self.min;
        let mut max = self.max;
        for axis in 0..3 {
            let delta = dir.coord(axis) * amount;
            if delta < 0 {
                min = min.with_coord(axis, min.coord(axis) + delta);
            } else if delta > 0 {
                max = max.with_coord(axis, max.coord(axis) + delta);
            }
        }
        Self::new(min, max)
    }

    /// Shrinks the region by pulling the face `dir` points at toward the opposite one (`/contract`).
    ///
    /// The Java implementation negates the direction and then moves the *min* face when the result
    /// is negative, so `/contract up` grows the region downward instead of shrinking it. The port
    /// follows the documented behaviour ("shrinks the region") rather than that bug.
    pub fn contract(self, dir: BlockPos, amount: i32) -> Self {
        let mut min = self.min;
        let mut max = self.max;
        for axis in 0..3 {
            let face = dir.coord(axis);
            if face > 0 {
                max = max.with_coord(axis, max.coord(axis) - amount);
            } else if face < 0 {
                min = min.with_coord(axis, min.coord(axis) + amount);
            }
        }
        if min.x > max.x || min.y > max.y || min.z > max.z {
            let c = BlockPos::new(
                (self.min.x + self.max.x) / 2,
                (self.min.y + self.max.y) / 2,
                (self.min.z + self.max.z) / 2,
            );
            Self::new(c, c)
        } else {
            Self::new(min, max)
        }
    }

    /// Moves the whole box (`/shift`).
    pub fn shift(self, dir: BlockPos, amount: i32) -> Self {
        Self::new(
            self.min.offset(dir.x * amount, dir.y * amount, dir.z * amount),
            self.max.offset(dir.x * amount, dir.y * amount, dir.z * amount),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalises_corners_and_counts_cells() {
        let b = BlockBox::new(BlockPos::new(4, 9, -2), BlockPos::new(1, 3, 2));
        assert_eq!(b.min(), BlockPos::new(1, 3, -2));
        assert_eq!(b.max(), BlockPos::new(4, 9, 2));
        assert_eq!(b.size(), [4, 7, 5]);
        assert_eq!(b.volume(), 140);
        assert_eq!(b.iter().count(), 140);
    }

    #[test]
    fn wall_boundary_and_face_predicates() {
        let b = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(2, 3, 2));
        assert!(b.is_wall(BlockPos::new(0, 1, 1)));
        assert!(!b.is_wall(BlockPos::new(1, 1, 1)));
        assert!(b.is_boundary(BlockPos::new(1, 0, 1)));
        assert!(!b.is_boundary(BlockPos::new(1, 1, 1)));
        assert!(b.is_horizontal_face(BlockPos::new(1, 3, 1)));
        assert!(!b.is_horizontal_face(BlockPos::new(1, 2, 1)));
    }

    #[test]
    fn center_spans_two_blocks_on_odd_extents() {
        let odd = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(3, 3, 3));
        let c = odd.center_box();
        assert_eq!(c.min(), BlockPos::new(1, 1, 1));
        assert_eq!(c.max(), BlockPos::new(2, 2, 2));
        assert_eq!(c.volume(), 8);

        let even = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(4, 4, 4));
        let c = even.center_box();
        assert_eq!(c.min(), BlockPos::new(2, 2, 2));
        assert_eq!(c.max(), BlockPos::new(2, 2, 2));
    }

    #[test]
    fn expand_contract_and_shift() {
        let b = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(3, 3, 3));
        let up = b.expand(BlockPos::new(0, 1, 0), 2);
        assert_eq!(up.max(), BlockPos::new(3, 5, 3));
        assert_eq!(up.min(), b.min());

        let down = b.expand(BlockPos::new(0, -1, 0), 2);
        assert_eq!(down.min(), BlockPos::new(0, -2, 0));

        let contracted = b.contract(BlockPos::new(0, 1, 0), 3);
        assert_eq!(contracted.max(), BlockPos::new(3, 0, 3));

        let shifted = b.shift(BlockPos::new(1, 0, 0), 5);
        assert_eq!(shifted.min(), BlockPos::new(5, 0, 0));
        assert_eq!(shifted.max(), BlockPos::new(8, 3, 3));
    }
}
