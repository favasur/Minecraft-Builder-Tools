//! Offset rotatable blocks: a block placed off the grid, turned any way.
//!
//! The Java mod keeps such a block as an entity with a yaw and a pitch (`OffGridBlockEntity`), draws
//! it with a `BlockDisplay`, and gives it a box that turns with it (`OffGridTransform`,
//! `RotatedBlockTriangles`, `RotatedCollisionProvider`, `FreeBlockRaycast`). Everything in that
//! pipeline that is arithmetic is here, and only the arithmetic: a transform, the oriented box it
//! produces, the ray test that picks the block, and the voxel walk that finds candidates.
//!
//! Horizon's build has no hook for a block's collision shape or for a custom mining raycast, so a
//! ported off-grid block can be *placed, snapped, drawn and hit-tested* with this module while its
//! collision is whatever the host gives the cell — the split is recorded in the README rather than
//! hidden here.
//!
//! Conventions follow the Java sources: `yaw` turns about the world's Y axis, `pitch` about X, both
//! in degrees, and the transform is applied to a unit cube centred on the block's cell before the
//! offset moves it. `matrix` is row-major 3×4, the same layout `ModelInstance::transform` takes, so
//! a rotated block's copy is one instance submission.

use super::pos::BlockPos;

/// How far a transform may be from the grid and still count as aligned.
pub const GRID_EPSILON: f64 = 1.0e-6;

/// A block's placement off the grid: where its centre sits and how it is turned.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct OffGridTransform {
    /// Offset of the block's centre from its cell's centre, in blocks.
    pub offset: [f64; 3],
    /// Turn about Y, in degrees. `0` faces north (`-Z`), as the game's yaw does.
    pub yaw: f64,
    /// Turn about X, in degrees. `0` is upright.
    pub pitch: f64,
}

impl OffGridTransform {
    /// A block exactly on the grid.
    pub const GRID: Self = Self {
        offset: [0.0, 0.0, 0.0],
        yaw: 0.0,
        pitch: 0.0,
    };

    pub const fn new(offset: [f64; 3], yaw: f64, pitch: f64) -> Self {
        Self {
            offset,
            yaw,
            pitch,
        }
    }

    /// The rotation as rows: `R * p` for a point `p` in the block's own space.
    pub fn rotation(&self) -> [[f64; 3]; 3] {
        let (yaw, pitch) = (self.yaw.to_radians(), self.pitch.to_radians());
        let (sy, cy) = (yaw.sin(), yaw.cos());
        let (sp, cp) = (pitch.sin(), pitch.cos());
        // R = Ry(yaw) * Rx(pitch), matching the Java placement preview: yaw turns the block about
        // the world's Y, pitch tips it about its own X afterwards.
        [
            [cy, sy * sp, sy * cp],
            [0.0, cp, -sp],
            [-sy, cy * sp, cy * cp],
        ]
    }

    /// The rotation as a row-major 3×4 affine matrix: `R * p + offset`.
    pub fn matrix(&self) -> [f64; 12] {
        let r = self.rotation();
        [
            r[0][0], r[0][1], r[0][2], self.offset[0],
            r[1][0], r[1][1], r[1][2], self.offset[1],
            r[2][0], r[2][1], r[2][2], self.offset[2],
        ]
    }

    /// Applies the transform to a point in the block's own space.
    pub fn apply(&self, point: [f64; 3]) -> [f64; 3] {
        let m = self.matrix();
        [
            m[0] * point[0] + m[1] * point[1] + m[2] * point[2] + m[3],
            m[4] * point[0] + m[5] * point[1] + m[6] * point[2] + m[7],
            m[8] * point[0] + m[9] * point[1] + m[10] * point[2] + m[11],
        ]
    }

    /// Turns a world point back into the block's own space, which is how a click on a turned block
    /// becomes a point on its face. `None` when the transform is not finite.
    pub fn apply_inverse(&self, point: [f64; 3]) -> Option<[f64; 3]> {
        if !self.yaw.is_finite()
            || !self.pitch.is_finite()
            || self.offset.iter().any(|value| !value.is_finite())
        {
            return None;
        }
        let r = self.rotation();
        let d = [
            point[0] - self.offset[0],
            point[1] - self.offset[1],
            point[2] - self.offset[2],
        ];
        // A rotation's inverse is its transpose.
        Some([
            r[0][0] * d[0] + r[1][0] * d[1] + r[2][0] * d[2],
            r[0][1] * d[0] + r[1][1] * d[1] + r[2][1] * d[2],
            r[0][2] * d[0] + r[1][2] * d[1] + r[2][2] * d[2],
        ])
    }

    /// Snaps to `step` blocks and `step_degrees`, the way a placement preview does, so two clicks
    /// that look alike give the same placement.
    pub fn snap(self, step: f64, step_degrees: f64) -> Self {
        let snap = |value: f64, step: f64| {
            if step <= 0.0 { value } else { (value / step).round() * step }
        };
        Self::new(
            [
                snap(self.offset[0], step),
                snap(self.offset[1], step),
                snap(self.offset[2], step),
            ],
            snap(self.yaw, step_degrees),
            snap(self.pitch, step_degrees),
        )
    }

    /// Whether this is the plain grid placement.
    pub fn is_grid_aligned(&self) -> bool {
        self.offset.iter().all(|value| value.abs() < GRID_EPSILON)
            && self.yaw.abs() < GRID_EPSILON
            && self.pitch.abs() < GRID_EPSILON
    }
}

/// An axis-aligned box in the block's own space, which the transform turns into an oriented one.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct UnitBox {
    pub min: [f64; 3],
    pub max: [f64; 3],
}

impl UnitBox {
    /// The block's whole cell, `0..1` on every axis (the game's block shape).
    pub const FULL: Self = Self {
        min: [0.0, 0.0, 0.0],
        max: [1.0, 1.0, 1.0],
    };

    pub const fn new(min: [f64; 3], max: [f64; 3]) -> Self {
        Self { min, max }
    }

    pub fn center(&self) -> [f64; 3] {
        [
            (self.min[0] + self.max[0]) / 2.0,
            (self.min[1] + self.max[1]) / 2.0,
            (self.min[2] + self.max[2]) / 2.0,
        ]
    }

    pub fn half(&self) -> [f64; 3] {
        [
            (self.max[0] - self.min[0]) / 2.0,
            (self.max[1] - self.min[1]) / 2.0,
            (self.max[2] - self.min[2]) / 2.0,
        ]
    }
}

/// A box turned with its block: centre, half extents and three unit axes.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct OrientedBox {
    pub center: [f64; 3],
    pub half: [f64; 3],
    /// The box's own X, Y and Z axes in world space.
    pub axes: [[f64; 3]; 3],
}

impl OrientedBox {
    /// The box a block shape takes at `transform`, centred on the cell at `cell` (whose corner is
    /// at `cell` in block coordinates).
    pub fn at(cell: BlockPos, shape: UnitBox, transform: &OffGridTransform) -> Self {
        // The rotation's trig runs once; its three columns are the box's turned axes.
        let r = transform.rotation();
        let rotated_axes = [
            [r[0][0], r[1][0], r[2][0]],
            [r[0][1], r[1][1], r[2][1]],
            [r[0][2], r[1][2], r[2][2]],
        ];
        let local_center = shape.center();
        // The shape's centre relative to the cell's centre, turned, then placed on the cell.
        let centered = [
            local_center[0] - 0.5,
            local_center[1] - 0.5,
            local_center[2] - 0.5,
        ];
        let turned = AnyVec::new(centered).rotate(rotated_axes);
        Self {
            center: [
                cell.x as f64 + 0.5 + turned[0] + transform.offset[0],
                cell.y as f64 + 0.5 + turned[1] + transform.offset[1],
                cell.z as f64 + 0.5 + turned[2] + transform.offset[2],
            ],
            half: shape.half(),
            axes: rotated_axes,
        }
    }

    /// The world-space axis-aligned box around it: the broad phase a collision or a culling test
    /// uses before the exact test.
    pub fn aabb(&self) -> ([f64; 3], [f64; 3]) {
        let extent = [
            self.half[0] * self.axes[0][0].abs()
                + self.half[1] * self.axes[1][0].abs()
                + self.half[2] * self.axes[2][0].abs(),
            self.half[0] * self.axes[0][1].abs()
                + self.half[1] * self.axes[1][1].abs()
                + self.half[2] * self.axes[2][1].abs(),
            self.half[0] * self.axes[0][2].abs()
                + self.half[1] * self.axes[1][2].abs()
                + self.half[2] * self.axes[2][2].abs(),
        ];
        (
            [
                self.center[0] - extent[0],
                self.center[1] - extent[1],
                self.center[2] - extent[2],
            ],
            [
                self.center[0] + extent[0],
                self.center[1] + extent[1],
                self.center[2] + extent[2],
            ],
        )
    }

    /// The eight corners, for drawing and for a convex test.
    pub fn corners(&self) -> [[f64; 3]; 8] {
        let mut out = [[0.0; 3]; 8];
        let mut index = 0;
        for sx in [-1.0, 1.0] {
            for sy in [-1.0, 1.0] {
                for sz in [-1.0, 1.0] {
                    out[index] = [
                        self.center[0]
                            + sx * self.half[0] * self.axes[0][0]
                            + sy * self.half[1] * self.axes[1][0]
                            + sz * self.half[2] * self.axes[2][0],
                        self.center[1]
                            + sx * self.half[0] * self.axes[0][1]
                            + sy * self.half[1] * self.axes[1][1]
                            + sz * self.half[2] * self.axes[2][1],
                        self.center[2]
                            + sx * self.half[0] * self.axes[0][2]
                            + sy * self.half[1] * self.axes[1][2]
                            + sz * self.half[2] * self.axes[2][2],
                    ];
                    index += 1;
                }
            }
        }
        out
    }

    /// Whether a point is inside, with a small tolerance so a point exactly on a face counts.
    pub fn contains(&self, point: [f64; 3]) -> bool {
        let d = [point[0] - self.center[0], point[1] - self.center[1], point[2] - self.center[2]];
        (0..3).all(|axis| {
            let distance = d[0] * self.axes[axis][0]
                + d[1] * self.axes[axis][1]
                + d[2] * self.axes[axis][2];
            distance.abs() <= self.half[axis] + 1.0e-9
        })
    }

    /// Where a ray enters the box, as a distance from `origin`, or `None` when it misses or starts
    /// inside. The slab test runs in the box's own axes, which is the whole point of carrying them.
    pub fn ray_intersection(&self, origin: [f64; 3], direction: [f64; 3]) -> Option<f64> {
        let d = [
            origin[0] - self.center[0],
            origin[1] - self.center[1],
            origin[2] - self.center[2],
        ];
        let mut t_min = f64::NEG_INFINITY;
        let mut t_max = f64::INFINITY;
        for axis in 0..3 {
            let o = d[0] * self.axes[axis][0]
                + d[1] * self.axes[axis][1]
                + d[2] * self.axes[axis][2];
            let v = direction[0] * self.axes[axis][0]
                + direction[1] * self.axes[axis][1]
                + direction[2] * self.axes[axis][2];
            let half = self.half[axis];
            if v.abs() < 1.0e-12 {
                if o.abs() > half {
                    return None;
                }
                continue;
            }
            let mut t1 = (-half - o) / v;
            let mut t2 = (half - o) / v;
            if t1 > t2 {
                std::mem::swap(&mut t1, &mut t2);
            }
            t_min = t_min.max(t1);
            t_max = t_max.min(t2);
            if t_min > t_max {
                return None;
            }
        }
        if t_max < 0.0 {
            return None;
        }
        // A ray that starts inside enters at 0.
        Some(t_min.max(0.0))
    }
}

/// A tiny helper so the rotation reads as a rotation.
struct AnyVec([f64; 3]);

impl AnyVec {
    const fn new(v: [f64; 3]) -> Self {
        Self(v)
    }

    fn rotate(self, axes: [[f64; 3]; 3]) -> [f64; 3] {
        // The value is in the block's own axes; expressing it in world axes is the sum of the
        // components along each turned axis.
        [
            self.0[0] * axes[0][0] + self.0[1] * axes[1][0] + self.0[2] * axes[2][0],
            self.0[0] * axes[0][1] + self.0[1] * axes[1][1] + self.0[2] * axes[2][1],
            self.0[0] * axes[0][2] + self.0[1] * axes[1][2] + self.0[2] * axes[2][2],
        ]
    }
}

/// Every cell a ray passes through, in order, up to `max_distance` blocks.
///
/// This is the voxel walk the Java `FreeBlockRaycast` uses to find the cell a click is over: the
/// grid itself is only candidates, and the exact hit is the oriented box test on each.
pub fn cells_on_ray(origin: [f64; 3], direction: [f64; 3], max_distance: f64) -> Vec<BlockPos> {
    let length = (direction[0] * direction[0]
        + direction[1] * direction[1]
        + direction[2] * direction[2])
        .sqrt();
    if length < 1.0e-9 || !max_distance.is_finite() || max_distance <= 0.0 {
        return Vec::new();
    }
    let d = [
        direction[0] / length,
        direction[1] / length,
        direction[2] / length,
    ];
    let mut cell = [
        origin[0].floor() as i32,
        origin[1].floor() as i32,
        origin[2].floor() as i32,
    ];
    let step = [
        if d[0] > 0.0 { 1 } else { -1 },
        if d[1] > 0.0 { 1 } else { -1 },
        if d[2] > 0.0 { 1 } else { -1 },
    ];
    let mut t_delta = [f64::INFINITY; 3];
    let mut t_max = [f64::INFINITY; 3];
    for axis in 0..3 {
        if d[axis].abs() > 1.0e-12 {
            t_delta[axis] = (1.0 / d[axis]).abs();
            let boundary = if step[axis] > 0 {
                (cell[axis] + 1) as f64
            } else {
                cell[axis] as f64
            };
            t_max[axis] = (boundary - origin[axis]) / d[axis];
        }
    }

    let mut out = Vec::new();
    loop {
        out.push(BlockPos::new(cell[0], cell[1], cell[2]));
        let axis = if t_max[0] <= t_max[1] && t_max[0] <= t_max[2] {
            0
        } else if t_max[1] <= t_max[2] {
            1
        } else {
            2
        };
        if t_max[axis] > max_distance || !t_max[axis].is_finite() {
            break;
        }
        cell[axis] += step[axis];
        t_max[axis] += t_delta[axis];
        if out.len() > 4_096 {
            break;
        }
    }
    out
}

/// The first off-grid block a ray hits: the nearest oriented box among the cells it passes through.
pub fn raycast<F>(origin: [f64; 3], direction: [f64; 3], max_distance: f64, mut lookup: F) -> Option<(BlockPos, f64)>
where
    F: FnMut(BlockPos) -> Option<(UnitBox, OffGridTransform)>,
{
    let mut best: Option<(BlockPos, f64)> = None;
    for cell in cells_on_ray(origin, direction, max_distance) {
        let Some((shape, transform)) = lookup(cell) else {
            continue;
        };
        let box_ = OrientedBox::at(cell, shape, &transform);
        if let Some(t) = box_.ray_intersection(origin, direction)
            && t <= max_distance
            && best.is_none_or(|(_, best_t)| t < best_t)
        {
            best = Some((cell, t));
        }
    }
    best
}

/// Walks the cells a ray crosses, calling `visit` with each and stopping when it returns `false`.
///
/// The allocation-free form of [`cells_on_ray`]: the hot path (picking, off-grid raycasts) calls this
/// and never builds a vector. Rust's zero-cost closures make it as fast as the loop inline, while
/// the `Vec` version stays for the tests and the debug overlay.
pub fn for_each_cell_on_ray(
    origin: [f64; 3],
    direction: [f64; 3],
    max_distance: f64,
    mut visit: impl FnMut(BlockPos) -> bool,
) {
    let length = (direction[0] * direction[0]
        + direction[1] * direction[1]
        + direction[2] * direction[2])
        .sqrt();
    if length < 1.0e-9 || !max_distance.is_finite() || max_distance <= 0.0 {
        return;
    }
    let d = [
        direction[0] / length,
        direction[1] / length,
        direction[2] / length,
    ];
    let mut cell = [
        origin[0].floor() as i32,
        origin[1].floor() as i32,
        origin[2].floor() as i32,
    ];
    let step = [
        if d[0] > 0.0 { 1 } else { -1 },
        if d[1] > 0.0 { 1 } else { -1 },
        if d[2] > 0.0 { 1 } else { -1 },
    ];
    let mut t_delta = [f64::INFINITY; 3];
    let mut t_max = [f64::INFINITY; 3];
    for axis in 0..3 {
        if d[axis].abs() > 1.0e-12 {
            t_delta[axis] = (1.0 / d[axis]).abs();
            let boundary = if step[axis] > 0 {
                (cell[axis] + 1) as f64
            } else {
                cell[axis] as f64
            };
            t_max[axis] = (boundary - origin[axis]) / d[axis];
        }
    }

    let mut visited = 0;
    loop {
        if !visit(BlockPos::new(cell[0], cell[1], cell[2])) {
            return;
        }
        visited += 1;
        if visited > 4_096 {
            return;
        }
        let axis = if t_max[0] <= t_max[1] && t_max[0] <= t_max[2] {
            0
        } else if t_max[1] <= t_max[2] {
            1
        } else {
            2
        };
        if t_max[axis] > max_distance || !t_max[axis].is_finite() {
            return;
        }
        cell[axis] += step[axis];
        t_max[axis] += t_delta[axis];
    }
}

/// A transform in the form the per-frame path wants: `f32`, rotation precomputed, laid out as nine
/// numbers and an offset so a batch of blocks is contiguous memory.
///
/// Rust's advantage here is not a trick but a consequence of the type system: the f64 form is exact
/// and pleasant to author against, and this packed form is what the loop over thousands of blocks
/// uses — half the memory traffic, no trigonometry at all, and the ray turned into the block's own
/// space **once** instead of three dot products per axis. Both are the same math, checked against
/// each other in the tests.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PackedTransform {
    /// Row-major 3x3 rotation.
    pub rotation: [f32; 9],
    pub offset: [f32; 3],
}

impl PackedTransform {
    pub fn from_transform(transform: &OffGridTransform) -> Self {
        let r = transform.rotation();
        Self {
            rotation: [
                r[0][0] as f32,
                r[0][1] as f32,
                r[0][2] as f32,
                r[1][0] as f32,
                r[1][1] as f32,
                r[1][2] as f32,
                r[2][0] as f32,
                r[2][1] as f32,
                r[2][2] as f32,
            ],
            offset: [
                transform.offset[0] as f32,
                transform.offset[1] as f32,
                transform.offset[2] as f32,
            ],
        }
    }

    /// The grid placement, which is the identity.
    pub const IDENTITY: Self = Self {
        rotation: [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0],
        offset: [0.0; 3],
    };

    #[inline]
    pub fn apply(&self, point: [f32; 3]) -> [f32; 3] {
        let r = &self.rotation;
        [
            r[0] * point[0] + r[1] * point[1] + r[2] * point[2] + self.offset[0],
            r[3] * point[0] + r[4] * point[1] + r[5] * point[2] + self.offset[1],
            r[6] * point[0] + r[7] * point[1] + r[8] * point[2] + self.offset[2],
        ]
    }

    /// World point into the block's own space (the rotation's inverse is its transpose).
    #[inline]
    pub fn apply_inverse(&self, point: [f32; 3]) -> [f32; 3] {
        let r = &self.rotation;
        let d = [
            point[0] - self.offset[0],
            point[1] - self.offset[1],
            point[2] - self.offset[2],
        ];
        [
            r[0] * d[0] + r[3] * d[1] + r[6] * d[2],
            r[1] * d[0] + r[4] * d[1] + r[7] * d[2],
            r[2] * d[0] + r[5] * d[1] + r[8] * d[2],
        ]
    }

    /// Whether a world point is inside a box of `half` extents centred on `center`.
    #[inline]
    pub fn contains(&self, center: [f32; 3], half: [f32; 3], point: [f32; 3]) -> bool {
        let local = self.apply_inverse(point);
        let center = self.apply_inverse(center);
        (local[0] - center[0]).abs() <= half[0]
            && (local[1] - center[1]).abs() <= half[1]
            && (local[2] - center[2]).abs() <= half[2]
    }

    /// Where a ray enters a box of `half` extents at `center`, in the block's own space: the ray is
    /// turned once, then an axis-aligned slab test runs, which is the whole reason for packing.
    #[inline]
    pub fn ray_hit(
        &self,
        center: [f32; 3],
        half: [f32; 3],
        origin: [f32; 3],
        direction: [f32; 3],
    ) -> Option<f32> {
        let o = self.apply_inverse(origin);
        let c = self.apply_inverse(center);
        // A direction is turned by the transpose alone; translation does not apply to it.
        let r = &self.rotation;
        let d = [
            r[0] * direction[0] + r[3] * direction[1] + r[6] * direction[2],
            r[1] * direction[0] + r[4] * direction[1] + r[7] * direction[2],
            r[2] * direction[0] + r[5] * direction[1] + r[8] * direction[2],
        ];
        let mut t_min = f32::NEG_INFINITY;
        let mut t_max = f32::INFINITY;
        for axis in 0..3 {
            let relative = o[axis] - c[axis];
            if d[axis].abs() < 1.0e-8 {
                if relative.abs() > half[axis] {
                    return None;
                }
                continue;
            }
            let t1 = (-half[axis] - relative) / d[axis];
            let t2 = (half[axis] - relative) / d[axis];
            let (t1, t2) = if t1 > t2 { (t2, t1) } else { (t1, t2) };
            t_min = t_min.max(t1);
            t_max = t_max.min(t2);
            if t_min > t_max {
                return None;
            }
        }
        (t_max >= 0.0).then(|| t_min.max(0.0))
    }
}

/// The first off-grid block a ray hits, using the packed transform and no per-cell allocation.
///
/// This is the picking path the client runs every frame on the block under the crosshair.
pub fn raycast_packed<F>(
    origin: [f64; 3],
    direction: [f64; 3],
    max_distance: f64,
    mut lookup: F,
) -> Option<(BlockPos, f32)>
where
    F: FnMut(BlockPos) -> Option<(PackedTransform, [f32; 3], [f32; 3])>,
{
    let origin32 = [origin[0] as f32, origin[1] as f32, origin[2] as f32];
    let direction32 = [direction[0] as f32, direction[1] as f32, direction[2] as f32];
    let mut best: Option<(BlockPos, f32)> = None;
    for_each_cell_on_ray(origin, direction, max_distance, |cell| {
        let Some((transform, center, half)) = lookup(cell) else {
            return true;
        };
        if let Some(t) = transform.ray_hit(center, half, origin32, direction32)
            && t <= max_distance as f32
            && best.is_none_or(|(_, best_t)| t < best_t)
        {
            best = Some((cell, t));
        }
        true
    });
    best
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: [f64; 3], b: [f64; 3]) -> bool {
        (0..3).all(|i| (a[i] - b[i]).abs() < 1.0e-9)
    }

    #[test]
    fn the_grid_transform_changes_nothing() {
        assert!(OffGridTransform::GRID.is_grid_aligned());
        assert_eq!(OffGridTransform::GRID.apply([0.25, 0.5, 0.75]), [0.25, 0.5, 0.75]);
        let matrix = OffGridTransform::GRID.matrix();
        assert_eq!(
            matrix,
            [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0]
        );
    }

    #[test]
    fn a_quarter_turn_maps_the_axes_onto_each_other() {
        let turned = OffGridTransform::new([0.0, 0.0, 0.0], 90.0, 0.0);
        // +X (east) becomes -Z (north): a clockwise quarter turn seen from above.
        assert!(close(turned.apply([1.0, 0.0, 0.0]), [0.0, 0.0, -1.0]));
        assert!(close(turned.apply([0.0, 0.0, 1.0]), [1.0, 0.0, 0.0]));
        // Y is the turn's own axis.
        assert!(close(turned.apply([0.0, 1.0, 0.0]), [0.0, 1.0, 0.0]));
    }

    #[test]
    fn a_transform_and_its_inverse_cancel() {
        let transform = OffGridTransform::new([0.2, 0.5, -0.1], 33.0, 12.0);
        let point = [0.3, -0.4, 0.9];
        let there = transform.apply(point);
        let back = transform.apply_inverse(there).unwrap();
        assert!(close(back, point), "got {back:?}");
    }

    #[test]
    fn snapping_rounds_to_the_steps_the_preview_shows() {
        let loose = OffGridTransform::new([0.13, 0.06, -0.21], 11.0, -4.0);
        let snapped = loose.snap(0.25, 15.0);
        assert_eq!(snapped.offset, [0.25, 0.0, -0.25]);
        assert_eq!(snapped.yaw, 15.0);
        assert_eq!(snapped.pitch, 0.0);
        assert!(!snapped.is_grid_aligned());
        assert!(OffGridTransform::new([0.01, 0.0, 0.0], 0.0, 0.0).snap(0.25, 15.0).is_grid_aligned());
    }

    #[test]
    fn a_rotated_cube_has_the_wider_bounding_box_and_the_same_volume_centre() {
        let cell = BlockPos::new(0, 0, 0);
        let flat = OrientedBox::at(cell, UnitBox::FULL, &OffGridTransform::GRID);
        assert!(close(flat.aabb().0, [0.0; 3]));
        assert!(close(flat.aabb().1, [1.0; 3]));

        let turned = OrientedBox::at(
            cell,
            UnitBox::FULL,
            &OffGridTransform::new([0.0, 0.0, 0.0], 45.0, 0.0),
        );
        let (min, max) = turned.aabb();
        let root_half = std::f64::consts::SQRT_2 / 2.0;
        assert!((max[0] - 0.5 - root_half).abs() < 1.0e-9, "x extent {}", max[0]);
        assert!((min[0] - (0.5 - root_half)).abs() < 1.0e-9);
        assert!((max[2] - 0.5 - root_half).abs() < 1.0e-9);
        // The vertical extent is untouched by a yaw.
        assert!((min[1] - 0.0).abs() < 1.0e-9 && (max[1] - 1.0).abs() < 1.0e-9);
    }

    #[test]
    fn the_offset_moves_the_box_and_nothing_else() {
        let cell = BlockPos::new(4, 64, -3);
        let shifted = OrientedBox::at(
            cell,
            UnitBox::FULL,
            &OffGridTransform::new([0.0, 0.5, 0.0], 0.0, 0.0),
        );
        assert!(close(shifted.center, [4.5, 65.0, -2.5]));
        assert!(shifted.contains([4.5, 65.0, -2.5]));
        assert!(!shifted.contains([4.5, 64.4, -2.5]));
    }

    #[test]
    fn a_slab_shape_at_the_bottom_of_its_cell_turns_with_it() {
        let slab = UnitBox::new([0.0, 0.0, 0.0], [1.0, 0.5, 1.0]);
        let cell = BlockPos::new(0, 0, 0);
        let upright = OrientedBox::at(cell, slab, &OffGridTransform::GRID);
        assert!(close(upright.center, [0.5, 0.25, 0.5]));

        // A 90-degree pitch stands the slab on edge.
        let tipped = OrientedBox::at(
            cell,
            slab,
            &OffGridTransform::new([0.0, 0.0, 0.0], 0.0, 90.0),
        );
        let (min, max) = tipped.aabb();
        assert!((max[1] - min[1] - 1.0).abs() < 1.0e-9, "still one block tall");
        assert!((max[2] - min[2] - 0.5).abs() < 1.0e-9, "and half a block deep");
    }

    #[test]
    fn a_ray_hits_the_near_face_and_misses_a_gap() {
        let box_ = OrientedBox::at(BlockPos::new(0, 0, 0), UnitBox::FULL, &OffGridTransform::GRID);
        let hit = box_.ray_intersection([-2.0, 0.5, 0.5], [1.0, 0.0, 0.0]);
        assert!((hit.unwrap() - 2.0).abs() < 1.0e-9);

        let miss = box_.ray_intersection([-2.0, 1.5, 0.5], [1.0, 0.0, 0.0]);
        assert_eq!(miss, None);

        // A ray starting inside reports zero.
        let inside = box_.ray_intersection([0.5, 0.5, 0.5], [1.0, 0.0, 0.0]);
        assert_eq!(inside, Some(0.0));
    }

    #[test]
    fn a_rotated_box_is_hit_where_the_flat_one_is_not() {
        let cell = BlockPos::new(0, 0, 0);
        let turned = OffGridTransform::new([0.0, 0.0, 0.0], 45.0, 0.0);
        let box_ = OrientedBox::at(cell, UnitBox::FULL, &turned);
        let flat = OrientedBox::at(cell, UnitBox::FULL, &OffGridTransform::GRID);
        // A point beyond the flat cube's face but inside the turned one: a quarter turn widens the
        // cube's reach along X by the square root of two.
        let point = [1.15, 0.5, 0.5];
        assert!(box_.contains(point));
        assert!(!flat.contains(point));

        // The same ray reaches the turned cube before the flat one.
        let origin = [4.15, 0.5, 0.5];
        let direction = [-1.0, 0.0, 0.0];
        let turned_hit = box_.ray_intersection(origin, direction).unwrap();
        let flat_hit = flat.ray_intersection(origin, direction).unwrap();
        assert!(turned_hit < flat_hit, "{turned_hit} should come before {flat_hit}");
    }

    #[test]
    fn the_voxel_walk_visits_cells_in_order_without_gaps() {
        let cells = cells_on_ray([0.5, 0.5, 0.5], [1.0, 0.0, 0.0], 3.0);
        assert_eq!(cells.len(), 4);
        assert_eq!(cells[0], BlockPos::new(0, 0, 0));
        assert_eq!(cells[3], BlockPos::new(3, 0, 0));

        // A diagonal ray steps one axis at a time, never skipping a cell diagonally.
        let diagonal = cells_on_ray([0.5, 0.5, 0.5], [1.0, 1.0, 0.0], 2.0);
        for pair in diagonal.windows(2) {
            let (a, b) = (pair[0], pair[1]);
            let steps = (a.x - b.x).abs() + (a.y - b.y).abs() + (a.z - b.z).abs();
            assert_eq!(steps, 1, "{a:?} to {b:?} skipped a cell");
        }
    }

    #[test]
    fn the_packed_transform_matches_the_exact_one() {
        let transform = OffGridTransform::new([0.2, 0.5, -0.1], 37.0, -14.0);
        let packed = PackedTransform::from_transform(&transform);
        for point in [[0.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.3, -0.4, 0.9], [-2.0, 5.0, 0.25]] {
            let exact = transform.apply([point[0], point[1], point[2]]);
            let fast = packed.apply([point[0] as f32, point[1] as f32, point[2] as f32]);
            for axis in 0..3 {
                assert!(
                    (exact[axis] - fast[axis] as f64).abs() < 1.0e-4,
                    "forward {exact:?} vs {fast:?}"
                );
            }
            // The packed inverse returns to the point it started from.
            let back = packed.apply_inverse(fast);
            for axis in 0..3 {
                assert!(
                    (back[axis] - point[axis] as f32).abs() < 1.0e-4,
                    "inverse {back:?} vs {point:?}"
                );
            }
        }
    }

    #[test]
    fn the_packed_ray_hit_agrees_with_the_oriented_box() {
        let cell = BlockPos::new(0, 0, 0);
        let transform = OffGridTransform::new([0.0, 0.0, 0.0], 45.0, 0.0);
        let exact = OrientedBox::at(cell, UnitBox::FULL, &transform);
        let packed = PackedTransform::from_transform(&transform);
        let centre = [0.5f32, 0.5, 0.5];
        let half = [0.5f32, 0.5, 0.5];

        for origin in [[-2.0f32, 0.5, 0.5], [4.0, 0.5, 0.5], [0.5, 5.0, 0.5], [3.0, 3.0, 3.0]] {
            let direction = [centre[0] - origin[0], centre[1] - origin[1], centre[2] - origin[2]];
            let fast = packed.ray_hit(centre, half, origin, direction);
            let slow = exact.ray_intersection(
                [origin[0] as f64, origin[1] as f64, origin[2] as f64],
                [direction[0] as f64, direction[1] as f64, direction[2] as f64],
            );
            match (fast, slow) {
                (Some(fast), Some(slow)) => {
                    assert!((fast as f64 - slow).abs() < 1.0e-4, "{fast} vs {slow}");
                }
                (None, None) => {}
                (fast, slow) => panic!("disagree: {fast:?} vs {slow:?}"),
            }
        }

        // A miss stays a miss.
        assert!(packed
            .ray_hit(centre, half, [5.0, 5.0, 5.0], [1.0, 0.0, 0.0])
            .is_none());
    }

    #[test]
    fn the_packed_raycast_finds_what_the_exact_one_does() {
        let cell = BlockPos::new(1, 0, 0);
        let transform = OffGridTransform::new([0.0, 0.0, 0.0], 30.0, 10.0);
        let exact = raycast([0.5, 0.5, 0.5], [1.0, 0.0, 0.0], 6.0, |candidate| {
            (candidate == cell).then_some((UnitBox::FULL, transform))
        });
        let packed_transform = PackedTransform::from_transform(&transform);
        let fast = raycast_packed([0.5, 0.5, 0.5], [1.0, 0.0, 0.0], 6.0, |candidate| {
            (candidate == cell).then_some((packed_transform, [1.5, 0.5, 0.5], [0.5, 0.5, 0.5]))
        });
        assert_eq!(exact.map(|(cell, _)| cell), fast.map(|(cell, _)| cell));
        assert!(fast.is_some());
    }

    #[test]
    fn the_callback_walk_visits_the_same_cells_in_the_same_order() {
        let collected = cells_on_ray([0.5, 64.5, 0.5], [1.0, -0.05, 0.3], 16.0);
        let mut walked = Vec::new();
        for_each_cell_on_ray([0.5, 64.5, 0.5], [1.0, -0.05, 0.3], 16.0, |cell| {
            walked.push(cell);
            true
        });
        assert_eq!(collected, walked);

        // Stopping early ends the walk.
        let mut count = 0;
        for_each_cell_on_ray([0.5, 0.5, 0.5], [1.0, 0.0, 0.0], 32.0, |_| {
            count += 1;
            false
        });
        assert_eq!(count, 1);
    }

    #[test]
    fn the_raycast_picks_the_nearest_off_grid_block() {
        let near = BlockPos::new(1, 0, 0);
        let far = BlockPos::new(3, 0, 0);
        let hit = raycast([0.5, 0.5, 0.5], [1.0, 0.0, 0.0], 6.0, |cell| {
            if cell == near || cell == far {
                Some((UnitBox::FULL, OffGridTransform::GRID))
            } else {
                None
            }
        });
        assert_eq!(hit.map(|(cell, _)| cell), Some(near));

        // A turned block in the same cell is still found by the same walk.
        let turned = BlockPos::new(2, 0, 0);
        let hit = raycast([0.5, 0.5, 0.5], [1.0, 0.0, 0.0], 6.0, |cell| {
            (cell == turned).then_some((UnitBox::FULL, OffGridTransform::new([0.0; 3], 45.0, 0.0)))
        });
        assert_eq!(hit.map(|(cell, _)| cell), Some(turned));

        // Nothing in reach means no hit at all.
        assert_eq!(raycast([0.5, 0.5, 0.5], [1.0, 0.0, 0.0], 2.0, |_| None), None);
    }
}
