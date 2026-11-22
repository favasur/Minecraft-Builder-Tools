//! The Bézier wall arch, ported from `util/BezierGeometry.java`.
//!
//! A quadratic curve `P(t) = (1-t)^2 A + 2(1-t)t C + t^2 B` from the wall's root to the click
//! destination, pulled toward the extended wall itself. It leaves `A` along the wall (no early
//! bend) and terminates exactly on `B`. The curve is split into ~1 m voussoirs by arc length, and
//! each wedge is a curved slice of a 1 m thick band, extruded 1 m along the plane normal.

use super::arch::{ARC_SUBDIVISIONS, Vec3};
use super::wedge::{FaceKind, WedgeFace, wedge_triangles};

/// Half the band's depth and half its thickness (the same 1 m × 1 m voussoir as the arch).
pub const DEPTH_HALF: f64 = 0.5;
/// Samples used to accumulate the curve's arc length.
const ARC_SAMPLES: usize = 2048;

/// Shared parameters of one Bézier arch.
#[derive(Clone, Debug, PartialEq)]
pub struct BezierArch {
    pub a: Vec3,
    pub c: Vec3,
    pub b: Vec3,
    /// The plane normal (the extrusion axis).
    pub v: Vec3,
    /// The in-plane rise axis.
    pub w: Vec3,
    pub count: usize,
    /// `count + 1` parameter values from 0 to 1.
    pub ts: Vec<f64>,
}

/// One voussoir of the band.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BezierBlockData {
    pub a: Vec3,
    pub c: Vec3,
    pub b: Vec3,
    pub v: Vec3,
    pub t0: f64,
    pub t1: f64,
}

impl BezierBlockData {
    /// World-space point on the curve at parameter `t`.
    pub fn point(&self, t: f64) -> Vec3 {
        point(self.a, self.c, self.b, t)
    }

    /// Unnormalised first derivative at `t`.
    pub fn derivative(&self, t: f64) -> Vec3 {
        derivative(self.a, self.c, self.b, t)
    }

    /// Tangent unit vector at `t`.
    pub fn tangent(&self, t: f64) -> Vec3 {
        tangent(self.a, self.c, self.b, t)
    }

    /// In-plane unit normal at `t`: `v × tangent`.
    pub fn normal(&self, t: f64) -> Vec3 {
        let n = self.v.cross(self.tangent(t));
        if n.length() < 1.0e-9 {
            self.v.cross(self.b.sub(self.a).normalize())
        } else {
            n.normalize()
        }
    }

    /// The centre of the wedge's centerline.
    pub fn wedge_center(&self) -> Vec3 {
        self.point((self.t0 + self.t1) / 2.0)
    }

    /// The 8 corners of the voussoir, in the same A..H order as the arch wedges.
    pub fn wedge_vertices(&self) -> [Vec3; 8] {
        let p0 = self.point(self.t0);
        let p1 = self.point(self.t1);
        let n0 = self.normal(self.t0);
        let n1 = self.normal(self.t1);
        let depth = self.v.scale(DEPTH_HALF);
        let i0 = p0.sub(n0.scale(DEPTH_HALF));
        let o0 = p0.add(n0.scale(DEPTH_HALF));
        let i1 = p1.sub(n1.scale(DEPTH_HALF));
        let o1 = p1.add(n1.scale(DEPTH_HALF));
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

/// World-space point on the curve at parameter `t`.
pub fn point(a: Vec3, c: Vec3, b: Vec3, t: f64) -> Vec3 {
    let mt = 1.0 - t;
    a.scale(mt * mt).add(c.scale(2.0 * mt * t)).add(b.scale(t * t))
}

/// First derivative (unnormalised tangent).
pub fn derivative(a: Vec3, c: Vec3, b: Vec3, t: f64) -> Vec3 {
    let mt = 1.0 - t;
    a.scale(-2.0 * mt).add(c.scale(2.0 * (mt - t))).add(b.scale(2.0 * t))
}

/// Tangent unit vector.
pub fn tangent(a: Vec3, c: Vec3, b: Vec3, t: f64) -> Vec3 {
    let d = derivative(a, c, b, t);
    if d.length() < 1.0e-9 {
        b.sub(a).normalize()
    } else {
        d.normalize()
    }
}

/// Builds the arch: samples the curve, accumulates arc length and picks the parameters at each
/// ~1 m boundary, so the last voussoir ends exactly at `t = 1`.
pub fn build(a: Vec3, c: Vec3, b: Vec3, v: Vec3, w: Vec3) -> BezierArch {
    let n = ARC_SAMPLES;
    let mut cum = vec![0.0_f64; n + 1];
    let mut prev = point(a, c, b, 0.0);
    for i in 1..=n {
        let cur = point(a, c, b, i as f64 / n as f64);
        cum[i] = cum[i - 1] + prev.distance_to(cur);
        prev = cur;
    }
    let total = cum[n];
    let count = ((total.round() as usize).max(2)).max(2);
    let mut ts = vec![0.0_f64; count + 1];
    for m in 1..count {
        let target = total * m as f64 / count as f64;
        let (lo, hi) = bracket(&cum, target);
        let span = cum[hi] - cum[lo];
        let f = if span <= 1.0e-12 { 0.0 } else { (target - cum[lo]) / span };
        ts[m] = ((lo as f64 + f) / n as f64).clamp(0.0, 1.0);
    }
    ts[count] = 1.0;
    BezierArch { a, c, b, v, w, count, ts }
}

/// The sample interval containing `target` (the Java mod's binary search).
fn bracket(cum: &[f64], target: f64) -> (usize, usize) {
    let mut lo = 0;
    let mut hi = cum.len() - 1;
    while lo < hi - 1 {
        let mid = (lo + hi) / 2;
        if cum[mid] <= target { lo = mid } else { hi = mid }
    }
    (lo, hi)
}

/// A copy of `data` whose end parameter is extended, merging consecutive voussoirs that land in
/// the same cell while keeping the geometry tiled (`BezierGeometry.extend`).
pub fn extend(data: BezierBlockData, extra_t: f64) -> BezierBlockData {
    BezierBlockData {
        t1: data.t1 + extra_t,
        ..data
    }
}

/// Arc length of the curve over `[t0, t1]`, as the Java `BezierGeometry.arcLength`: a 16-step
/// polyline through the curve's own points.
fn arc_length(data: &BezierBlockData, t0: f64, t1: f64) -> f64 {
    const STEPS: usize = 16;
    let h = (t1 - t0) / STEPS as f64;
    let mut sum = 0.0;
    let mut prev = data.point(t0);
    for i in 1..=STEPS {
        let cur = data.point(t0 + i as f64 * h);
        sum += prev.distance_to(cur);
        prev = cur;
    }
    sum
}

impl BezierBlockData {
    /// The tessellated surface of this voussoir.
    ///
    /// Same layout as the arch wedges: the curved faces are split into [`ARC_SUBDIVISIONS`] quads
    /// with every corner exactly on the Bézier and the 0.5 m offset taken along the exact in-plane
    /// normal. The tile phase is measured from the arch's `t = 0`, so the texture continues across
    /// voussoir seams instead of restarting every metre. The shared end edge is nudged along
    /// `-tangent` so it cannot z-fight with the next voussoir's start face.
    pub fn wedge_faces(&self) -> Vec<WedgeFace> {
        let n = ARC_SUBDIVISIONS;
        let t0 = self.t0;
        let t1 = self.t1;
        let h = DEPTH_HALF;
        let v = self.v;
        let origin = Vec3::splat(0.0);
        let mut inner = vec![[origin; 2]; n + 1];
        let mut outer = vec![[origin; 2]; n + 1];
        let mut cum = vec![0.0; n + 1];

        for i in 0..=n {
            let t = t0 + (t1 - t0) * i as f64 / n as f64;
            let p = self.point(t);
            let nn = self.normal(t);
            let pi = p.sub(nn.scale(h));
            let po = p.add(nn.scale(h));
            inner[i][0] = pi.sub(v.scale(h));
            inner[i][1] = pi.add(v.scale(h));
            outer[i][0] = po.sub(v.scale(h));
            outer[i][1] = po.add(v.scale(h));
            cum[i] = arc_length(self, 0.0, t);
        }

        let inward = self.tangent(t1).scale(-1.0);
        for side in 0..2 {
            inner[n][side] = inner[n][side].add(inward.scale(0.0005));
            outer[n][side] = outer[n][side].add(inward.scale(0.0005));
        }

        let mut faces = Vec::with_capacity(4 * n + 2);
        for i in 0..n {
            faces.push(WedgeFace {
                corners: [inner[i][0], inner[i + 1][0], inner[i + 1][1], inner[i][1]],
                u: [cum[i], cum[i + 1], cum[i + 1], cum[i]],
                v: [0.0, 0.0, 1.0, 1.0],
                kind: FaceKind::Inner,
            });
            faces.push(WedgeFace {
                corners: [outer[i][0], outer[i][1], outer[i + 1][1], outer[i + 1][0]],
                u: [cum[i], cum[i], cum[i + 1], cum[i + 1]],
                v: [0.0, 1.0, 1.0, 0.0],
                kind: FaceKind::Outer,
            });
            faces.push(WedgeFace {
                corners: [inner[i][0], outer[i][0], outer[i + 1][0], inner[i + 1][0]],
                u: [cum[i], cum[i], cum[i + 1], cum[i + 1]],
                v: [0.0, 1.0, 1.0, 0.0],
                kind: FaceKind::Back,
            });
            faces.push(WedgeFace {
                corners: [inner[i][1], inner[i + 1][1], outer[i + 1][1], outer[i][1]],
                u: [cum[i], cum[i + 1], cum[i + 1], cum[i]],
                v: [0.0, 0.0, 1.0, 1.0],
                kind: FaceKind::Front,
            });
        }

        faces.push(WedgeFace {
            corners: [inner[0][0], inner[0][1], outer[0][1], outer[0][0]],
            u: [0.0, 0.0, 1.0, 1.0],
            v: [0.0, 1.0, 1.0, 0.0],
            kind: FaceKind::Start,
        });
        faces.push(WedgeFace {
            corners: [inner[n][0], outer[n][0], outer[n][1], inner[n][1]],
            u: [0.0, 1.0, 1.0, 0.0],
            v: [0.0, 0.0, 1.0, 1.0],
            kind: FaceKind::End,
        });
        faces
    }

    /// The closed wedge surface as triangles (2 per face).
    pub fn wedge_triangles(&self) -> Vec<[Vec3; 3]> {
        wedge_triangles(&self.wedge_faces())
    }

    /// Every face of this voussoir, each rebased and split for its own tile.
    pub fn tile_quads(&self) -> Vec<super::wedge::TileQuad> {
        self.wedge_faces()
            .iter()
            .flat_map(|face| face.tile_quads())
            .collect()
    }
}

impl BezierArch {
    /// The `i`-th voussoir, shifted by `offset` (a depth column or radial layer of the wall).
    pub fn block_data(&self, i: usize, offset: Vec3) -> BezierBlockData {
        BezierBlockData {
            a: self.a.add(offset),
            c: self.c.add(offset),
            b: self.b.add(offset),
            v: self.v,
            t0: self.ts[i],
            t1: self.ts[i + 1],
        }
    }

    /// The curve's arc length, from the samples `build` took.
    pub fn arc_length(&self) -> f64 {
        let n = ARC_SAMPLES;
        let mut total = 0.0;
        let mut prev = point(self.a, self.c, self.b, 0.0);
        for i in 1..=n {
            let cur = point(self.a, self.c, self.b, i as f64 / n as f64);
            total += prev.distance_to(cur);
            prev = cur;
        }
        total
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn straight_arch() -> BezierArch {
        // A, C and B collinear: the curve is the straight segment from A to B.
        build(
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(5.0, 0.0, 0.0),
            Vec3::new(10.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            Vec3::new(0.0, 1.0, 0.0),
        )
    }

    #[test]
    fn the_curve_ends_exactly_on_the_destination() {
        let arch = straight_arch();
        assert!((arch.ts[0] - 0.0).abs() < 1e-12);
        assert!((arch.ts[arch.count] - 1.0).abs() < 1e-12);
        let end = point(arch.a, arch.c, arch.b, 1.0);
        assert!((end.distance_to(arch.b)).abs() < 1e-12);
    }

    #[test]
    fn a_ten_metre_straight_band_splits_into_ten_one_metre_voussoirs() {
        let arch = straight_arch();
        assert_eq!(arch.count, 10);
        assert!((arch.arc_length() - 10.0).abs() < 1e-6);
        for i in 0..arch.count - 1 {
            let data = arch.block_data(i, Vec3::splat(0.0));
            let width = data.wedge_center().distance_to(
                arch.block_data(i + 1, Vec3::splat(0.0)).wedge_center(),
            );
            assert!((width - 1.0).abs() < 1e-6, "voussoir {i} is {width} m wide");
        }
    }

    #[test]
    fn arc_length_splitting_is_uniform_on_a_curved_band() {
        let arch = build(
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(6.0, 8.0, 0.0),
            Vec3::new(12.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            Vec3::new(0.0, 1.0, 0.0),
        );
        // Consecutive voussoirs are ~1 m apart along the curve.
        for i in 0..arch.count - 1 {
            let a = arch.block_data(i, Vec3::splat(0.0)).wedge_center();
            let b = arch.block_data(i + 1, Vec3::splat(0.0)).wedge_center();
            let width = a.distance_to(b);
            assert!((width - 1.0).abs() < 0.05, "voussoir {i} is {width} m wide");
        }
    }

    #[test]
    fn the_band_is_one_metre_thick_and_one_metre_deep() {
        let arch = straight_arch();
        let data = arch.block_data(3, Vec3::splat(0.0));
        let v = data.wedge_vertices();
        // A..B are 1 m apart across the band, A..E are 1 m apart along it.
        assert!((v[0].distance_to(v[1]) - 1.0).abs() < 1e-9);
        assert!((v[0].distance_to(v[4]) - 1.0).abs() < 1e-9);
    }

    #[test]
    fn a_band_voussoir_tessellates_and_splits_only_when_a_tile_is_crossed() {
        let arch = straight_arch();
        let data = arch.block_data(2, Vec3::splat(0.0));
        let faces = data.wedge_faces();
        assert_eq!(faces.len(), 4 * ARC_SUBDIVISIONS + 2);
        assert_eq!(data.wedge_triangles().len(), 2 * faces.len());
        // A 1 m voussoir stays inside one tile per quad, so nothing needs splitting.
        for face in &faces {
            assert_eq!(face.tile_quads().len(), 1, "face {:?} split", face.kind);
        }
    }

    #[test]
    fn extend_stretches_the_end_parameter() {
        let arch = straight_arch();
        let data = arch.block_data(2, Vec3::splat(0.0));
        let longer = super::extend(data, 0.25);
        assert!((longer.t1 - (data.t1 + 0.25)).abs() < 1e-12);
        assert_eq!(longer.t0, data.t0);
    }

    #[test]
    fn the_normal_stays_perpendicular_to_the_depth_axis() {
        let arch = straight_arch();
        let data = arch.block_data(2, Vec3::splat(0.0));
        for t in [0.0, 0.25, 0.5, 1.0] {
            let n = data.normal(data.t0 + (data.t1 - data.t0) * t);
            assert!(n.dot(arch.v).abs() < 1e-9);
        }
    }
}
