//! The arching math, ported from `util/ArchGeometry.java`.
//!
//! A straight row of 1 m cubes becomes a circular arch of tapered voussoirs. Given the chord span
//! `S` and the rise `H`, the centreline radius is `R = H/2 + S^2 / (8H)` and the arc spans
//! `pi - 2*atan2(R - H, S/2)` radians. The port keeps the Java formulas exactly, including the
//! semicircle clamp and the fan fallback, because the server commit and the client ghost preview
//! both derive from them and must agree.

use super::pos::{BlockBox, BlockPos};

/// Minimum rise (m) below which a click counts as being on the chord itself.
pub const MIN_RISE: f64 = 0.5;
/// Half the voussoir's depth along the arch's extrusion axis.
pub const DEPTH_HALF: f64 = 0.5;
/// Subdivisions per voussoir along the arc, so the band renders as a curve.
pub const ARC_SUBDIVISIONS: usize = 8;

/// A world-space vector (Minecraft's `Vec3`).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Vec3 {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl Vec3 {
    pub const fn new(x: f64, y: f64, z: f64) -> Self {
        Self { x, y, z }
    }

    pub const fn splat(v: f64) -> Self {
        Self::new(v, v, v)
    }

    pub fn add(self, other: Self) -> Self {
        Self::new(self.x + other.x, self.y + other.y, self.z + other.z)
    }

    pub fn sub(self, other: Self) -> Self {
        Self::new(self.x - other.x, self.y - other.y, self.z - other.z)
    }

    pub fn scale(self, factor: f64) -> Self {
        Self::new(self.x * factor, self.y * factor, self.z * factor)
    }

    pub fn dot(self, other: Self) -> f64 {
        self.x * other.x + self.y * other.y + self.z * other.z
    }

    pub fn cross(self, other: Self) -> Self {
        Self::new(
            self.y * other.z - self.z * other.y,
            self.z * other.x - self.x * other.z,
            self.x * other.y - self.y * other.x,
        )
    }

    pub fn length(self) -> f64 {
        self.dot(self).sqrt()
    }

    pub fn distance_to(self, other: Self) -> f64 {
        self.sub(other).length()
    }

    pub fn normalize(self) -> Self {
        let len = self.length();
        if len < 1.0e-9 { self } else { self.scale(1.0 / len) }
    }
}

/// The six block faces, in Minecraft's order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Face {
    Down,
    Up,
    North,
    South,
    West,
    East,
}

impl Face {
    pub const fn normal(self) -> [i32; 3] {
        match self {
            Face::Down => [0, -1, 0],
            Face::Up => [0, 1, 0],
            Face::North => [0, 0, -1],
            Face::South => [0, 0, 1],
            Face::West => [-1, 0, 0],
            Face::East => [1, 0, 0],
        }
    }
}

/// The local coordinate frame of a clicked block face (`util/FaceFrame.java`): the face centre is
/// the origin, the face normal is `forward`, and `right`/`up` span the face plane. A wall face
/// gives an upright arch or ring, a floor/ceiling face a sideways one.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FaceFrame {
    pub origin: Vec3,
    pub forward: Vec3,
    pub right: Vec3,
    pub up: Vec3,
}

impl FaceFrame {
    pub fn of(cell: BlockPos, face: Face) -> Self {
        let n = face.normal();
        let forward = Vec3::new(n[0] as f64, n[1] as f64, n[2] as f64);
        let (right, up) = if n[1] != 0 {
            // Floor/ceiling: the plane is horizontal (XZ).
            (Vec3::new(1.0, 0.0, 0.0), Vec3::new(0.0, 0.0, 1.0))
        } else {
            // Wall: up is +Y, right completes the frame.
            let up = Vec3::new(0.0, 1.0, 0.0);
            (up.cross(forward), up)
        };
        let c = cell.center();
        let origin = Vec3::new(c[0], c[1], c[2]).add(forward.scale(0.5));
        Self {
            origin,
            forward,
            right,
            up,
        }
    }
}

/// The centre of `pos`'s face `face` (the Java mod's `FaceFrame.of(pos, face).origin()`).
pub fn face_center(pos: BlockPos, face: Face) -> Vec3 {
    FaceFrame::of(pos, face).origin
}

/// Shared parameters of one arch (identical for every voussoir).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ArchResult {
    pub origin: Vec3,
    pub u: Vec3,
    pub w: Vec3,
    pub radius: f64,
    pub theta_start: f64,
    pub total_angle: f64,
}

/// One voussoir's parameters.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ArchBlockData {
    pub origin: Vec3,
    pub u: Vec3,
    pub w: Vec3,
    pub theta_start: f64,
    pub delta_theta: f64,
    pub radius: f64,
}

impl ArchBlockData {
    pub fn inner_radius(&self) -> f64 {
        self.radius - DEPTH_HALF
    }

    pub fn outer_radius(&self) -> f64 {
        self.radius + DEPTH_HALF
    }

    /// The extrusion axis: perpendicular to the span/rise plane.
    pub fn depth(&self) -> Vec3 {
        self.u.cross(self.w)
    }

    /// World-space point on the arch at `theta` and radius `r`.
    pub fn polar(&self, theta: f64, r: f64) -> Vec3 {
        self.origin
            .add(self.u.scale(r * theta.cos()))
            .add(self.w.scale(r * theta.sin()))
    }

    /// The tangent at `theta` in the increasing-theta direction.
    pub fn tangent(&self, theta: f64) -> Vec3 {
        self.u
            .scale(-theta.sin())
            .add(self.w.scale(theta.cos()))
    }

    /// The centre of the wedge's centerline.
    pub fn wedge_center(&self) -> Vec3 {
        self.polar(self.theta_start + self.delta_theta / 2.0, self.radius)
    }

    /// The 8 corners of the voussoir, in the Java mod's A..H order:
    /// A (inner,start,-depth), B (outer,start,-depth), C (outer,end,-depth), D (inner,end,-depth),
    /// E (inner,start,+depth), F (outer,start,+depth), G (outer,end,+depth), H (inner,end,+depth).
    pub fn wedge_vertices(&self) -> [Vec3; 8] {
        let t0 = self.theta_start;
        let t1 = t0 + self.delta_theta;
        let depth = self.depth().scale(DEPTH_HALF);
        let i0 = self.polar(t0, self.inner_radius());
        let o0 = self.polar(t0, self.outer_radius());
        let i1 = self.polar(t1, self.inner_radius());
        let o1 = self.polar(t1, self.outer_radius());
        [
            i0.sub(depth),
            o0.sub(depth),
            o1.sub(depth),
            i1.sub(depth),
            i0.add(depth),
            o0.add(depth),
            o1.add(depth),
            i1.add(depth),
        ]
    }
}

/// The arch a click would commit for a region.
#[derive(Clone, Debug, PartialEq)]
pub enum RegionArch {
    /// The classic circular bow used for thin ribbons.
    Circular {
        arch: ArchResult,
        count: usize,
        span: f64,
        center: Vec3,
    },
    /// A wide wall, or a click beyond the wall's end, bows along a quadratic Bézier band
    /// (`BezierGeometry.build`). The band's length is free, so it always ends on the click.
    Bezier {
        arch: crate::core::bezier::BezierArch,
        count: usize,
        span: f64,
        center: Vec3,
    },
}

/// How the region was laid out, for the caller's messages.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ArchKind {
    Circular,
    Bezier,
}

impl RegionArch {
    pub fn kind(&self) -> ArchKind {
        match self {
            RegionArch::Circular { .. } => ArchKind::Circular,
            RegionArch::Bezier { .. } => ArchKind::Bezier,
        }
    }

    /// The number of voussoirs the shape is made of.
    pub fn count(&self) -> usize {
        match self {
            RegionArch::Circular { count, .. } | RegionArch::Bezier { count, .. } => *count,
        }
    }
}

/// Computes the arch through `start` and `end` in the plane of the unit vectors `u` and `w`,
/// bulging toward `+w` by the rise. Returns `None` when no valid arch can form.
pub fn compute_arch_in_plane(start: Vec3, end: Vec3, u: Vec3, w: Vec3, click: Vec3) -> Option<ArchResult> {
    let span = start.distance_to(end);
    if span < 1.0 {
        return None;
    }
    let mid = start.add(end).scale(0.5);
    let mut w = w;
    let mut rise = click.sub(mid).dot(w);
    if rise.abs() < MIN_RISE {
        return None;
    }
    if rise < 0.0 {
        w = w.scale(-1.0);
        rise = -rise;
    }
    // Never more than a semicircle: the arc always bulges toward the click.
    if rise > span / 2.0 {
        rise = span / 2.0;
    }
    let radius = rise / 2.0 + span * span / (8.0 * rise);
    let phi_b = (radius - rise).atan2(span / 2.0);
    let theta_start = std::f64::consts::PI - phi_b;
    let total = theta_start - phi_b;
    Some(ArchResult {
        origin: mid.sub(w.scale(radius - rise)),
        u,
        w,
        radius,
        theta_start,
        total_angle: total,
    })
}

/// The `i`-th voussoir of `count` for a circular arch (0 = the start end of the row).
pub fn block_data(arch: &ArchResult, i: usize, count: usize) -> ArchBlockData {
    let per_block = arch.total_angle / count as f64;
    let end = arch.theta_start - i as f64 * per_block;
    ArchBlockData {
        origin: arch.origin,
        u: arch.u,
        w: arch.w,
        theta_start: end - per_block,
        delta_theta: per_block,
        radius: arch.radius,
    }
}

/// The `i`-th voussoir of the fan fallback: radius `0.5 + i` around the pivot, with the bend
/// easing in quadratically so the first cells stay nearly straight.
pub fn fan_block_data(arch: &ArchResult, i: usize, count: usize) -> ArchBlockData {
    let n = count as f64;
    let t0 = arch.total_angle * (i * i) as f64 / (n * n);
    let t1 = arch.total_angle * ((i + 1) * (i + 1)) as f64 / (n * n);
    ArchBlockData {
        origin: arch.origin,
        u: arch.u,
        w: arch.w,
        theta_start: t0,
        delta_theta: t1 - t0,
        radius: 0.5 + i as f64,
    }
}

/// The centre of the region box (mean of cell centres).
pub fn box_center(region: BlockBox) -> Vec3 {
    let min = region.min();
    let max = region.max();
    Vec3::new(
        min.x as f64 + (max.x - min.x + 1) as f64 / 2.0,
        min.y as f64 + (max.y - min.y + 1) as f64 / 2.0,
        min.z as f64 + (max.z - min.z + 1) as f64 / 2.0,
    )
}

/// The span of the region's cell centres projected onto `axis`.
pub fn cell_extent(region: BlockBox, axis: Vec3) -> f64 {
    let min = region.min();
    let max = region.max();
    let mut lo = f64::INFINITY;
    let mut hi = f64::NEG_INFINITY;
    for dx in 0..=1 {
        for dy in 0..=1 {
            for dz in 0..=1 {
                let px = min.x as f64 + 0.5 + dx as f64 * (max.x - min.x) as f64;
                let py = min.y as f64 + 0.5 + dy as f64 * (max.y - min.y) as f64;
                let pz = min.z as f64 + 0.5 + dz as f64 * (max.z - min.z) as f64;
                let p = px * axis.x + py * axis.y + pz * axis.z;
                lo = lo.min(p);
                hi = hi.max(p);
            }
        }
    }
    hi - lo
}

/// Derives the arch a click on `face` would commit for the region, sharing the Java mod's
/// `ArchGeometry.regionArch` derivation for the circular case.
pub fn region_arch(region: BlockBox, click: BlockPos, face: Face) -> Option<RegionArch> {
    let min = region.min();
    let max = region.max();
    let ex = (max.x - min.x) as f64;
    let ey = (max.y - min.y) as f64;
    let ez = (max.z - min.z) as f64;
    let span = ex.max(ey).max(ez);
    if span < 1.0 {
        return None;
    }

    let mut u = if ex >= ey && ex >= ez {
        Vec3::new(1.0, 0.0, 0.0)
    } else if ey >= ez {
        Vec3::new(0.0, 1.0, 0.0)
    } else {
        Vec3::new(0.0, 0.0, 1.0)
    };

    let center = box_center(region);
    let click_point = face_center(click, face);
    let horiz = Vec3::new(click_point.x - center.x, 0.0, click_point.z - center.z);
    if horiz.dot(u) < 0.0 {
        u = u.scale(-1.0);
    }

    let perp = horiz.sub(u.scale(horiz.dot(u)));
    let rise = perp.length();
    let along = horiz.dot(u);
    let w = if rise >= 1.0e-4 {
        perp.scale(1.0 / rise)
    } else if ey >= ex && ey >= ez {
        let hx = if horiz.x.abs() > 1.0e-4 { horiz.x } else { 1.0 };
        let len = (hx * hx + horiz.z * horiz.z).sqrt();
        Vec3::new(hx / len, 0.0, horiz.z / len)
    } else if u.x != 0.0 {
        Vec3::new(0.0, 0.0, 1.0)
    } else {
        Vec3::new(1.0, 0.0, 0.0)
    };

    let ribbon = if u.x != 0.0 {
        ey <= 1.5 && ez <= 1.5
    } else if u.y != 0.0 {
        ex <= 1.5 && ez <= 1.5
    } else {
        ex <= 1.5 && ey <= 1.5
    };

    if ribbon && rise >= MIN_RISE {
        let start = center.sub(u.scale(span / 2.0));
        let end = center.add(u.scale(span / 2.0));
        let depth = cell_extent(region, w);
        let rise_eff = rise.min(depth / 2.0 + 1.5);
        if rise_eff < MIN_RISE {
            return None;
        }
        let arch = compute_arch_in_plane(start, end, u, w, center.add(w.scale(rise_eff * 1.0001)))?;
        let by_arc = (arch.total_angle * arch.radius).round();
        let count = (span.round() as usize + 1).max(by_arc as usize);
        return Some(RegionArch::Circular {
            arch,
            count,
            span,
            center,
        });
    }

    // A wall, or a click beyond the wall's end: the Bezier band, whose length is free so it always
    // terminates exactly on the click destination.
    let dist = (rise * rise + along * along).sqrt();
    if dist < 1.0 {
        return None;
    }
    let a = center.sub(u.scale(span / 2.0));
    let c = center.add(u.scale(span / 2.0));
    let b = center.add(horiz);
    if b.sub(a).dot(b.sub(a)) < 1.0 {
        return None;
    }
    let bezier = crate::core::bezier::build(a, c, b, u.cross(w), w);
    let count = bezier.count;
    Some(RegionArch::Bezier {
        arch: bezier,
        count,
        span,
        center,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn flat_row(len: i32) -> BlockBox {
        BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(len - 1, 0, 0))
    }

    #[test]
    fn radius_and_arc_follow_the_java_formulas() {
        let start = Vec3::new(0.0, 0.0, 0.0);
        let end = Vec3::new(8.0, 0.0, 0.0);
        let u = Vec3::new(1.0, 0.0, 0.0);
        let w = Vec3::new(0.0, 0.0, 1.0);
        let arch = compute_arch_in_plane(start, end, u, w, Vec3::new(4.0, 0.0, 2.0)).unwrap();

        // R = H/2 + S^2 / (8H) = 1 + 64/16 = 5
        assert!((arch.radius - 5.0).abs() < 1e-9);
        let phi_b = (5.0_f64 - 2.0).atan2(4.0);
        assert!((arch.theta_start - (std::f64::consts::PI - phi_b)).abs() < 1e-9);
        assert!((arch.total_angle - (std::f64::consts::PI - 2.0 * phi_b)).abs() < 1e-9);
    }

    #[test]
    fn rise_is_clamped_to_a_semicircle_and_rejects_the_chord() {
        let start = Vec3::new(0.0, 0.0, 0.0);
        let end = Vec3::new(4.0, 0.0, 0.0);
        let u = Vec3::new(1.0, 0.0, 0.0);
        let w = Vec3::new(0.0, 0.0, 1.0);

        // A click on the chord itself cannot form an arch.
        assert!(compute_arch_in_plane(start, end, u, w, Vec3::new(2.0, 0.0, 0.4)).is_none());

        // A huge click is clamped to a semicircle: R = H/2 + S^2/(8H) with H = S/2 = 2 -> 2.
        let arch = compute_arch_in_plane(start, end, u, w, Vec3::new(2.0, 0.0, 50.0)).unwrap();
        assert!((arch.radius - 2.0).abs() < 1e-9);
        assert!((arch.total_angle - std::f64::consts::PI).abs() < 1e-9);
    }

    #[test]
    fn pushed_toward_the_negative_rise_flips_the_bulge() {
        let start = Vec3::new(0.0, 0.0, 0.0);
        let end = Vec3::new(8.0, 0.0, 0.0);
        let u = Vec3::new(1.0, 0.0, 0.0);
        let w = Vec3::new(0.0, 0.0, 1.0);
        let arch = compute_arch_in_plane(start, end, u, w, Vec3::new(4.0, 0.0, -2.0)).unwrap();
        assert!(arch.w.z < 0.0);
        assert!((arch.radius - 5.0).abs() < 1e-9);
    }

    #[test]
    fn voussoirs_tile_the_whole_arc_with_no_gaps() {
        let arch = compute_arch_in_plane(
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(8.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            Vec3::new(4.0, 0.0, 2.0),
        )
        .unwrap();

        let count = 9;
        let total: f64 = (0..count)
            .map(|i| block_data(&arch, i, count).delta_theta)
            .sum();
        assert!((total - arch.total_angle).abs() < 1e-9);

        // Wedge 0's end sits on the row's start angle; consecutive wedges share their edge.
        let first = block_data(&arch, 0, count);
        assert!((first.theta_start + first.delta_theta - arch.theta_start).abs() < 1e-9);
        for i in 1..count {
            let prev = block_data(&arch, i - 1, count);
            let cur = block_data(&arch, i, count);
            assert!((prev.theta_start - (cur.theta_start + cur.delta_theta)).abs() < 1e-9);
        }
    }

    #[test]
    fn wedge_vertices_sit_on_the_inner_and_outer_circles() {
        let arch = compute_arch_in_plane(
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(8.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            Vec3::new(4.0, 0.0, 2.0),
        )
        .unwrap();
        let data = block_data(&arch, 3, 9);
        let v = data.wedge_vertices();

        // Every corner sits on the inner or the outer circle once the 0.5 m depth offset is removed.
        let depth = data.depth();
        for corner in v {
            let rel = corner.sub(data.origin);
            let along = rel.dot(depth);
            let radial = rel.sub(depth.scale(along)).length();
            assert!(
                (radial - data.inner_radius()).abs() < 1e-9
                    || (radial - data.outer_radius()).abs() < 1e-9
            );
        }
        // The wedge is exactly 1 m thick from the inner to the outer arc.
        assert!((data.outer_radius() - data.inner_radius() - 1.0).abs() < 1e-9);
        // Depth is 1 m along the extrusion axis.
        assert!(((v[4].sub(v[0])).dot(depth) - 1.0).abs() < 1e-9);
    }

    #[test]
    fn fan_eases_the_bend_in_from_the_first_cell() {
        let arch = ArchResult {
            origin: Vec3::splat(0.0),
            u: Vec3::new(1.0, 0.0, 0.0),
            w: Vec3::new(0.0, 0.0, 1.0),
            radius: 0.0,
            theta_start: 0.0,
            total_angle: 1.0,
        };
        let first = fan_block_data(&arch, 0, 4);
        let second = fan_block_data(&arch, 1, 4);
        let last = fan_block_data(&arch, 3, 4);
        // Quadratically eased: the first cell turns far less than the last.
        assert!(first.delta_theta < second.delta_theta);
        assert!(second.delta_theta < last.delta_theta);
        assert!((last.theta_start + last.delta_theta - 1.0).abs() < 1e-9);
        assert!((first.radius - 0.5).abs() < 1e-9);
    }

    #[test]
    fn a_thin_row_arches_circularly_and_a_click_beside_it_does_too() {
        let plan = region_arch(flat_row(8), BlockPos::new(4, 0, 5), Face::Up).unwrap();
        assert_eq!(plan.kind(), ArchKind::Circular);
        match plan {
            RegionArch::Circular { count, span, .. } => {
                assert!((span - 7.0).abs() < 1e-9);
                assert!(count >= 8);
            }
            RegionArch::Bezier { .. } => unreachable!(),
        }
    }

    #[test]
    fn a_block_wide_wall_takes_the_bezier_band() {
        let wall = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(7, 4, 0));
        match region_arch(wall, BlockPos::new(4, 2, 6), Face::Up) {
            Some(RegionArch::Bezier { arch, count, .. }) => {
                // The band ends exactly on the click destination.
                assert!((arch.ts[count] - 1.0).abs() < 1e-12);
                assert!(count >= 2);
            }
            other => panic!("expected a Bezier band, got {other:?}"),
        }
    }

    #[test]
    fn the_face_frame_orients_walls_upright_and_floors_flat() {
        // A north wall face: forward is -Z, up is +Y.
        let wall = FaceFrame::of(BlockPos::new(0, 0, 0), Face::North);
        assert_eq!(wall.forward, Vec3::new(0.0, 0.0, -1.0));
        assert_eq!(wall.up, Vec3::new(0.0, 1.0, 0.0));
        assert!(wall.right.dot(wall.forward).abs() < 1e-12);

        // A floor face: the plane is horizontal.
        let floor = FaceFrame::of(BlockPos::new(0, 0, 0), Face::Up);
        assert_eq!(floor.right, Vec3::new(1.0, 0.0, 0.0));
        assert_eq!(floor.up, Vec3::new(0.0, 0.0, 1.0));
    }

    #[test]
    fn zero_span_regions_have_no_arch() {
        let single = BlockBox::new(BlockPos::new(3, 3, 3), BlockPos::new(3, 3, 3));
        assert!(region_arch(single, BlockPos::new(3, 3, 6), Face::Up).is_none());
    }
}
