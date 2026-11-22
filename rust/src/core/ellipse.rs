//! The ellipse building mechanic, ported from `util/EllipseGeometry.java`.
//!
//! A placed rectangle becomes a closed ring of voussoirs following an ellipse. Equal *angular*
//! steps would give unequal block widths, so the ring is split into `round(perimeter)` equal
//! ~1 m steps of *arc length*, found by numerically integrating `ds/dt = sqrt(a² sin²t + b² cos²t)`
//! and inverting it. Every voussoir is then ~1 m wide at the centerline and the loop closes exactly.

use super::arch::{ARC_SUBDIVISIONS, Face, FaceFrame, Vec3, box_center, cell_extent};
use super::wedge::{FaceKind, WedgeFace, wedge_triangles};
use super::pos::{BlockBox, BlockPos};

/// Half the wedge's radial offset and half its depth: a 1 m thick, 1 m deep band.
pub const DEPTH_HALF: f64 = 0.5;
/// Samples for the arc-length integration of the perimeter.
const ARC_SAMPLES: usize = 8192;
/// Below this squared-curvature factor at the tips (`b²/a`) the ring's inner edge would pinch.
pub const MIN_CURVATURE: f64 = 0.6;

/// A ring's shared parameters.
#[derive(Clone, Debug, PartialEq)]
pub struct EllipseResult {
    pub center: Vec3,
    pub u: Vec3,
    pub w: Vec3,
    /// The extrusion axis, `u × w`.
    pub v: Vec3,
    pub a: f64,
    pub b: f64,
    pub perimeter: f64,
    pub count: usize,
    /// `count + 1` boundary angles, with the last exactly `2π`.
    pub thetas: Vec<f64>,
}

/// One voussoir of the ring.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct EllipseBlockData {
    pub center: Vec3,
    pub u: Vec3,
    pub w: Vec3,
    pub a: f64,
    pub b: f64,
    pub theta_start: f64,
    pub delta_theta: f64,
}

impl EllipseBlockData {
    /// World-space centerline point at angle `t`.
    pub fn centerline(&self, t: f64) -> Vec3 {
        self.center
            .add(self.u.scale(self.a * t.cos()))
            .add(self.w.scale(self.b * t.sin()))
    }

    /// Outward unit normal at `t`: `(b cos t, a sin t)` normalised, in the u/w plane.
    pub fn normal(&self, t: f64) -> Vec3 {
        let nx = self.b * t.cos();
        let ny = self.a * t.sin();
        let len = (nx * nx + ny * ny).sqrt();
        if len < 1.0e-9 {
            self.w
        } else {
            self.u.scale(nx / len).add(self.w.scale(ny / len))
        }
    }

    /// Tangent unit vector at `t`, in the increasing-`t` direction.
    pub fn tangent(&self, t: f64) -> Vec3 {
        let tan = self
            .u
            .scale(-self.a * t.sin())
            .add(self.w.scale(self.b * t.cos()));
        if tan.length() < 1.0e-9 {
            self.w
        } else {
            tan.normalize()
        }
    }

    /// The extrusion axis.
    pub fn depth(&self) -> Vec3 {
        self.u.cross(self.w)
    }

    /// The centre of the wedge's centerline.
    pub fn wedge_center(&self) -> Vec3 {
        self.centerline(self.theta_start + self.delta_theta / 2.0)
    }

    /// The 8 corners, in the arch wedges' A..H order.
    pub fn wedge_vertices(&self) -> [Vec3; 8] {
        let t0 = self.theta_start;
        let t1 = t0 + self.delta_theta;
        let depth = self.depth().scale(DEPTH_HALF);
        let p0 = self.centerline(t0);
        let p1 = self.centerline(t1);
        let n0 = self.normal(t0);
        let n1 = self.normal(t1);
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

/// A copy of `data` extended by `extra` radians, merging consecutive voussoirs that land in the
/// same cell while keeping the geometry tiled. The server commit uses this when two voussoirs
/// fall in one cell; the ported core does not place blocks yet, so it is currently unused.
#[allow(dead_code)]
pub fn extend(data: EllipseBlockData, extra: f64) -> EllipseBlockData {
    EllipseBlockData {
        delta_theta: data.delta_theta + extra,
        ..data
    }
}

/// Builds the ring: numeric perimeter, `count = round(perimeter)` equal arc-length steps, and the
/// angle of every step boundary.
pub fn build_ellipse(center: Vec3, u: Vec3, w: Vec3, a: f64, b: f64) -> EllipseResult {
    let d_theta = std::f64::consts::TAU / ARC_SAMPLES as f64;
    let mut s = vec![0.0_f64; ARC_SAMPLES + 1];
    for i in 1..=ARC_SAMPLES {
        let t0 = (i - 1) as f64 * d_theta;
        let t1 = i as f64 * d_theta;
        let v0 = (a * a * t0.sin().powi(2) + b * b * t0.cos().powi(2)).sqrt();
        let v1 = (a * a * t1.sin().powi(2) + b * b * t1.cos().powi(2)).sqrt();
        s[i] = s[i - 1] + (v0 + v1) / 2.0 * d_theta;
    }
    let perimeter = s[ARC_SAMPLES];
    let count = ((perimeter.round() as usize).max(6)).max(6);
    let step = perimeter / count as f64;
    let mut thetas = vec![0.0_f64; count + 1];
    for (i, theta) in thetas.iter_mut().enumerate().take(count) {
        *theta = inverse_arc_length(&s, d_theta, i as f64 * step);
    }
    thetas[count] = std::f64::consts::TAU;
    EllipseResult {
        center,
        u,
        w,
        v: u.cross(w),
        a,
        b,
        perimeter,
        count,
        thetas,
    }
}

impl EllipseResult {
    /// The `i`-th voussoir, at depth layer `layer` (0 = the region's first depth cell).
    pub fn block_data(&self, i: usize, layer: i32) -> EllipseBlockData {
        let offset = self.v.scale(layer as f64);
        EllipseBlockData {
            center: self.center.add(offset),
            u: self.u,
            w: self.w,
            a: self.a,
            b: self.b,
            theta_start: self.thetas[i],
            delta_theta: self.thetas[i + 1] - self.thetas[i],
        }
    }
}

/// The angle whose cumulative arc length equals `target`.
fn inverse_arc_length(s: &[f64], d_theta: f64, target: f64) -> f64 {
    let mut lo = 0;
    let mut hi = s.len() - 1;
    while hi - lo > 1 {
        let mid = (lo + hi) / 2;
        if s[mid] <= target { lo = mid } else { hi = mid }
    }
    let span = s[hi] - s[lo];
    let f = if span <= 1.0e-12 { 0.0 } else { (target - s[lo]) / span };
    (lo as f64 + f.clamp(0.0, 1.0)) * d_theta
}

/// The ring a click on `face` would commit for the region, with its depth-layer count.
#[derive(Clone, Debug, PartialEq)]
pub struct RegionEllipse {
    pub ellipse: EllipseResult,
    pub layers: i32,
}

/// Derives the ring for the region and clicked face.
pub fn region_ellipse(region: BlockBox, click: BlockPos, face: Face) -> Option<RegionEllipse> {
    let frame = FaceFrame::of(click, face);
    let u = frame.right;
    let w = frame.up;
    let extent_u = cell_extent(region, u);
    let extent_w = cell_extent(region, w);
    if extent_u < 1.0 || extent_w < 1.0 {
        return None;
    }
    let a = extent_u / 2.0;
    let b = extent_w / 2.0;
    if a - 0.5 < 0.5 || b - 0.5 < 0.5 {
        return None;
    }
    if b * b / a < MIN_CURVATURE {
        return None;
    }
    let extent_v = cell_extent(region, frame.forward);
    let layers = extent_v.floor() as i32 + 1;
    Some(RegionEllipse {
        ellipse: build_ellipse(box_center(region), u, w, a, b),
        layers,
    })
}

/// Trapezoid arc length of the ellipse over `[t0, t1]`, as the Java `EllipseGeometry.arcLength`.
fn arc_length(a: f64, b: f64, t0: f64, t1: f64) -> f64 {
    const STEPS: usize = 16;
    let h = (t1 - t0) / STEPS as f64;
    let mut sum = 0.0;
    for i in 0..STEPS {
        let ta = t0 + i as f64 * h;
        let tb = ta + h;
        let va = (a * a * ta.sin().powi(2) + b * b * ta.cos().powi(2)).sqrt();
        let vb = (a * a * tb.sin().powi(2) + b * b * tb.cos().powi(2)).sqrt();
        sum += (va + vb) / 2.0 * h;
    }
    sum
}

impl EllipseBlockData {
    /// The tessellated surface of this voussoir.
    ///
    /// Same layout as the arch wedges: the curved faces are split into [`ARC_SUBDIVISIONS`] quads
    /// with every corner exactly on the ellipse and the 0.5 m offset taken along the exact outward
    /// normal. The tile phase is measured from the ellipse's own `theta = 0`, so the texture runs
    /// continuously around the whole loop instead of restarting every metre. The shared end edge is
    /// nudged along `-tangent` (the ellipse's end face faces outward along `+tangent`).
    pub fn wedge_faces(&self) -> Vec<WedgeFace> {
        let n = ARC_SUBDIVISIONS;
        let t0 = self.theta_start;
        let t1 = t0 + self.delta_theta;
        let h = DEPTH_HALF;
        let v = self.depth();
        let origin = Vec3::splat(0.0);
        let mut inner = vec![[origin; 2]; n + 1];
        let mut outer = vec![[origin; 2]; n + 1];
        let mut cum = vec![0.0; n + 1];

        for i in 0..=n {
            let t = t0 + (t1 - t0) * i as f64 / n as f64;
            let p = self.centerline(t);
            let nn = self.normal(t);
            let pi = p.sub(nn.scale(h));
            let po = p.add(nn.scale(h));
            inner[i][0] = pi.sub(v.scale(h));
            inner[i][1] = pi.add(v.scale(h));
            outer[i][0] = po.sub(v.scale(h));
            outer[i][1] = po.add(v.scale(h));
            cum[i] = arc_length(self.a, self.b, 0.0, t);
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

#[cfg(test)]
mod tests {
    use super::*;

    fn circle() -> EllipseResult {
        build_ellipse(
            Vec3::splat(0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            4.0,
            4.0,
        )
    }

    #[test]
    fn a_circle_perimeter_matches_two_pi_r() {
        let ring = circle();
        assert!((ring.perimeter - std::f64::consts::TAU * 4.0).abs() < 1e-6);
        assert_eq!(ring.count, 25); // round(8π) = 25
    }

    #[test]
    fn the_loop_closes_exactly() {
        let ring = circle();
        assert_eq!(ring.thetas.len(), ring.count + 1);
        assert!((ring.thetas[0]).abs() < 1e-12);
        assert!((ring.thetas[ring.count] - std::f64::consts::TAU).abs() < 1e-12);
    }

    #[test]
    fn every_voussoir_is_about_one_metre_wide_on_a_flat_ellipse() {
        let ring = build_ellipse(
            Vec3::splat(0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            8.0,
            3.0,
        );
        for i in 0..ring.count - 1 {
            let a = ring.block_data(i, 0).wedge_center();
            let b = ring.block_data(i + 1, 0).wedge_center();
            // Centre-to-centre distance along the ring stays near the 1 m target even though the
            // angles are very uneven (that is the point of the arc-length split).
            let width = a.distance_to(b);
            assert!((0.9..1.1).contains(&width), "voussoir {i} is {width} m wide");
        }
    }

    #[test]
    fn the_wedge_is_one_metre_thick_and_the_vertices_ring_the_centerline() {
        let ring = circle();
        let data = ring.block_data(3, 0);
        let v = data.wedge_vertices();
        let depth = data.depth();
        // Inner to outer corners are 1 m apart.
        assert!((v[0].distance_to(v[1]) - 1.0).abs() < 1e-9);
        // The two depth sides are 1 m apart.
        assert!((v[0].distance_to(v[4]) - 1.0).abs() < 1e-9);
        assert!((v[4].sub(v[0])).dot(depth).abs() > 0.99);
    }

    #[test]
    fn layers_offset_the_ring_along_the_depth_axis() {
        let ring = circle();
        let layer0 = ring.block_data(5, 0);
        let layer2 = ring.block_data(5, 2);
        let delta = layer2.center.sub(layer0.center);
        assert!((delta.length() - 2.0).abs() < 1e-9);
        assert!((delta.dot(ring.v) - 2.0).abs() < 1e-9);
    }

    #[test]
    fn a_flat_region_is_refused_by_the_curvature_guard() {
        // 1×1 in the face plane: extents below 1 m.
        let thin = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(0, 0, 0));
        assert!(region_ellipse(thin, BlockPos::new(0, 1, 0), Face::Up).is_none());
    }

    #[test]
    fn a_ring_voussoir_tessellates_like_a_roof_wedge() {
        let ring = circle();
        let data = ring.block_data(3, 0);
        let faces = data.wedge_faces();
        assert_eq!(faces.len(), 4 * ARC_SUBDIVISIONS + 2);
        assert_eq!(data.wedge_triangles().len(), 2 * faces.len());
        // Phases are absolute around the loop, so a voussoir away from theta = 0 crosses a tile
        // edge and is cut in two — and every resulting phase still fits its own tile.
        let mut cut = 0;
        for face in faces.iter().filter(|f| f.kind == FaceKind::Inner) {
            let quads = face.tile_quads();
            assert!(quads.len() <= 2);
            if quads.len() == 2 {
                cut += 1;
            }
            for quad in quads {
                assert!(quad.u.iter().all(|u| (0.0..=1.0).contains(u)));
            }
        }
        assert!(cut > 0, "a mid-ring voussoir should cross a tile edge");
    }

    #[test]
    fn a_square_region_produces_a_ring_with_the_expected_depth_layers() {
        let region = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(7, 0, 7));
        let plan = region_ellipse(region, BlockPos::new(4, 1, 4), Face::Up).unwrap();
        // Flat region in Y: one depth layer.
        assert_eq!(plan.layers, 1);
        assert!(plan.ellipse.count >= 6);
        // Cell-centre extent of a 0..7 region is 7 blocks, so the centerline semi-axes are 3.5.
        assert!((plan.ellipse.a - 3.5).abs() < 1e-9);
        assert!((plan.ellipse.b - 3.5).abs() < 1e-9);
    }
}
