//! The tessellated surface of a voussoir, shared by the arch, the ellipse ring and the Bézier
//! band (Java `ArchGeometry.WedgeFace`, `wedgeFaces`, `addFaceQuads`, `wedgeTriangles`).
//!
//! A wedge is 8 corners and 6 logical faces, but the curved faces (inner/outer arc and the two
//! depth faces) are split into [`ARC_SUBDIVISIONS`] quads so the shape renders and collides as a
//! smooth curve. Each quad carries its tile coordinates: `u` along the arc in metres, `v` across it.
//! [`WedgeFace::tile_quads`] rebases those back into `[0, 1]` per tile and splits a quad that
//! straddles a tile boundary — the fix that stopped wedge textures from being stretched into
//! duplicated stripes at every junction.

use super::arch::{ARC_SUBDIVISIONS, ArchBlockData, DEPTH_HALF, Face, Vec3};

/// One of the six logical faces of a voussoir.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FaceKind {
    Inner,
    Outer,
    Start,
    End,
    Back,
    Front,
}

impl FaceKind {
    /// The vanilla face whose sprite the Java mod samples for this wedge face: top texture on the
    /// outer arc, bottom on the inner arc, side textures on the joints and ends.
    pub const fn sprite_face(self) -> Face {
        match self {
            FaceKind::Inner => Face::Down,
            FaceKind::Outer => Face::Up,
            FaceKind::Start | FaceKind::End | FaceKind::Back | FaceKind::Front => Face::North,
        }
    }
}

/// One tessellated quad of a voussoir: 4 world-space corners wound outward, the tile coordinates at
/// each corner, and which logical face it belongs to.
#[derive(Clone, Debug, PartialEq)]
pub struct WedgeFace {
    pub corners: [Vec3; 4],
    /// Arc length along the curve, in metres (the tile phase, before rebasing).
    pub u: [f64; 4],
    /// Across the face, normally exactly 0 or 1.
    pub v: [f64; 4],
    pub kind: FaceKind,
}

/// A quad ready to draw: `u`/`v` rebased into `[0, 1]` for its own tile, and split at tile
/// boundaries so no tile is ever stretched.
#[derive(Clone, Debug, PartialEq)]
pub struct TileQuad {
    pub corners: [Vec3; 4],
    pub u: [f64; 4],
    pub v: [f64; 4],
    pub kind: FaceKind,
}

impl TileQuad {
    /// The two triangles of the quad, in world space.
    pub fn triangles(&self) -> [[Vec3; 3]; 2] {
        let c = self.corners;
        [[c[0], c[1], c[2]], [c[0], c[2], c[3]]]
    }
}

/// Outward unit normal of a quad from its winding.
pub fn outward_normal(p0: Vec3, p1: Vec3, p2: Vec3) -> Vec3 {
    let n = p1.sub(p0).cross(p2.sub(p0));
    if n.length() < 1.0e-9 {
        Vec3::new(0.0, 1.0, 0.0)
    } else {
        n.normalize()
    }
}

impl WedgeFace {
    pub fn outward_normal(&self) -> Vec3 {
        outward_normal(self.corners[0], self.corners[1], self.corners[2])
    }

    /// The quad with its `u` phase rebased onto its own tile.
    fn unsplit(&self) -> TileQuad {
        let base = self.u.iter().copied().fold(f64::INFINITY, f64::min).floor();
        TileQuad {
            corners: self.corners,
            u: [
                self.u[0] - base,
                self.u[1] - base,
                self.u[2] - base,
                self.u[3] - base,
            ],
            v: self.v,
            kind: self.kind,
        }
    }

    /// The quads to draw: one, or two when the quad straddles a tile boundary.
    ///
    /// The mirrored arc-length integration drifts a few centimetres, so a sub-quad can end just
    /// past a tile edge. Clamping there would stretch the tile's far column into a duplicated
    /// stripe at every junction; splitting exactly at the boundary instead lets the next tile start
    /// cleanly. `v` is passed through untouched, so a corner at exactly 1.0 stays on the tile's far
    /// edge rather than snapping back to 0.
    pub fn tile_quads(&self) -> Vec<TileQuad> {
        let min_u = self.u.iter().copied().fold(f64::INFINITY, f64::min);
        let max_u = self.u.iter().copied().fold(f64::NEG_INFINITY, f64::max);
        let base = min_u.floor();
        let boundary = max_u.floor();

        if boundary > base && max_u > boundary {
            let f = (boundary - min_u) / (max_u - min_u);
            // Pair the min-u corners with the max-u corners by their v, so each edge keeps its own v.
            let mut ai = [usize::MAX; 2];
            let mut bi = [usize::MAX; 2];
            for j in 0..4 {
                let slot = if self.v[j] == 0.0 { 0 } else { 1 };
                if self.u[j] == min_u {
                    ai[slot] = j;
                } else {
                    bi[slot] = j;
                }
            }
            if ai.iter().chain(bi.iter()).any(|i| *i == usize::MAX) {
                return vec![self.unsplit()];
            }

            let c = self.corners;
            let s0 = c[ai[0]].add(c[bi[0]].sub(c[ai[0]]).scale(f));
            let s1 = c[ai[1]].add(c[bi[1]].sub(c[ai[1]]).scale(f));
            let low_u = min_u - base;
            let high_u = max_u - boundary;
            let v0 = [self.v[ai[0]], self.v[ai[0]], self.v[ai[1]], self.v[ai[1]]];

            vec![
                TileQuad {
                    corners: [c[ai[0]], s0, s1, c[ai[1]]],
                    u: [low_u, 1.0, 1.0, low_u],
                    v: v0,
                    kind: self.kind,
                },
                TileQuad {
                    corners: [s0, c[bi[0]], c[bi[1]], s1],
                    u: [0.0, high_u, high_u, 0.0],
                    v: v0,
                    kind: self.kind,
                },
            ]
        } else {
            vec![self.unsplit()]
        }
    }
}

/// Two triangles per face, in world space — the surface the exact-mesh collision and raycasting use.
pub fn wedge_triangles(faces: &[WedgeFace]) -> Vec<[Vec3; 3]> {
    let mut tris = Vec::with_capacity(faces.len() * 2);
    for face in faces {
        let c = face.corners;
        tris.push([c[0], c[1], c[2]]);
        tris.push([c[0], c[2], c[3]]);
    }
    tris
}

impl ArchBlockData {
    /// The tessellated surface of this voussoir.
    ///
    /// The arc and depth faces are split into [`ARC_SUBDIVISIONS`] quads with every corner exactly
    /// on the circle; the two radial faces stay single flat quads. The end-radial edge is nudged a
    /// hair along `+tangent` so it cannot z-fight with the next voussoir's coincident start face.
    pub fn wedge_faces(&self) -> Vec<WedgeFace> {
        let n = ARC_SUBDIVISIONS;
        let t0 = self.theta_start;
        let t1 = t0 + self.delta_theta;
        let h = DEPTH_HALF;
        let v = self.depth();
        let ri = self.inner_radius();
        let ro = self.outer_radius();
        let rm = self.radius;

        let origin = Vec3::splat(0.0);
        let mut inner = vec![[origin; 2]; n + 1];
        let mut outer = vec![[origin; 2]; n + 1];
        for i in 0..=n {
            let t = t0 + (t1 - t0) * i as f64 / n as f64;
            let pi = self.polar(t, ri);
            let po = self.polar(t, ro);
            inner[i][0] = pi.sub(v.scale(h));
            inner[i][1] = pi.add(v.scale(h));
            outer[i][0] = po.sub(v.scale(h));
            outer[i][1] = po.add(v.scale(h));
        }

        let inward = self.tangent(t1);
        for side in 0..2 {
            inner[n][side] = inner[n][side].add(inward.scale(0.0005));
            outer[n][side] = outer[n][side].add(inward.scale(0.0005));
        }

        let mut faces = Vec::with_capacity(4 * n + 2);
        for i in 0..n {
            let ri0 = ri * (t0 + self.delta_theta * i as f64 / n as f64);
            let ri1 = ri * (t0 + self.delta_theta * (i + 1) as f64 / n as f64);
            let ro0 = ro * (t0 + self.delta_theta * i as f64 / n as f64);
            let ro1 = ro * (t0 + self.delta_theta * (i + 1) as f64 / n as f64);
            let rm0 = rm * (t0 + self.delta_theta * i as f64 / n as f64);
            let rm1 = rm * (t0 + self.delta_theta * (i + 1) as f64 / n as f64);

            faces.push(WedgeFace {
                corners: [inner[i][0], inner[i + 1][0], inner[i + 1][1], inner[i][1]],
                u: [ri0, ri1, ri1, ri0],
                v: [0.0, 0.0, 1.0, 1.0],
                kind: FaceKind::Inner,
            });
            faces.push(WedgeFace {
                corners: [outer[i][0], outer[i][1], outer[i + 1][1], outer[i + 1][0]],
                u: [ro0, ro0, ro1, ro1],
                v: [0.0, 1.0, 1.0, 0.0],
                kind: FaceKind::Outer,
            });
            faces.push(WedgeFace {
                corners: [inner[i][0], outer[i][0], outer[i + 1][0], inner[i + 1][0]],
                u: [rm0, rm0, rm1, rm1],
                v: [0.0, 1.0, 1.0, 0.0],
                kind: FaceKind::Back,
            });
            faces.push(WedgeFace {
                corners: [inner[i][1], inner[i + 1][1], outer[i + 1][1], outer[i][1]],
                u: [rm0, rm1, rm1, rm0],
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

    /// Every face of this voussoir, each already rebased and split for its own tile.
    pub fn tile_quads(&self) -> Vec<TileQuad> {
        self.wedge_faces()
            .iter()
            .flat_map(|face| face.tile_quads())
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::arch::{block_data, compute_arch_in_plane};

    fn wedge() -> ArchBlockData {
        let arch = compute_arch_in_plane(
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(8.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            Vec3::new(4.0, 0.0, 2.0),
        )
        .unwrap();
        block_data(&arch, 2, 9)
    }

    #[test]
    fn a_voussoir_has_four_quads_per_subdivision_plus_two_ends() {
        let faces = wedge().wedge_faces();
        assert_eq!(faces.len(), 4 * ARC_SUBDIVISIONS + 2);
        assert_eq!(faces.len(), 34);
        let inner = faces.iter().filter(|f| f.kind == FaceKind::Inner).count();
        let outer = faces.iter().filter(|f| f.kind == FaceKind::Outer).count();
        assert_eq!(inner, ARC_SUBDIVISIONS);
        assert_eq!(outer, ARC_SUBDIVISIONS);
        assert_eq!(faces.iter().filter(|f| f.kind == FaceKind::Start).count(), 1);
        assert_eq!(faces.iter().filter(|f| f.kind == FaceKind::End).count(), 1);
    }

    #[test]
    fn the_surface_is_closed_with_two_triangles_per_face() {
        let tris = wedge().wedge_triangles();
        assert_eq!(tris.len(), 2 * (4 * ARC_SUBDIVISIONS + 2));
        assert_eq!(tris.len(), 68);
    }

    #[test]
    fn flat_end_faces_never_split_and_every_quad_fits_its_tile() {
        let data = wedge();
        for face in data.wedge_faces() {
            let quads = face.tile_quads();
            // One cut at most: a voussoir's arc is shorter than two tiles.
            assert!(quads.len() <= 2, "face {:?} needed {} quads", face.kind, quads.len());
            for quad in &quads {
                assert!(
                    quad.u.iter().all(|u| (0.0..=1.0).contains(u)),
                    "face {:?} left a phase outside its tile: {:?}",
                    face.kind,
                    quad.u
                );
                assert!(quad.v.iter().all(|v| *v == 0.0 || *v == 1.0));
            }
        }
        // The radial joints are exactly one tile wide, so they must stay a single quad.
        for face in data
            .wedge_faces()
            .iter()
            .filter(|f| f.kind == FaceKind::Start || f.kind == FaceKind::End)
        {
            assert_eq!(face.tile_quads().len(), 1);
        }
    }

    #[test]
    fn arc_phases_run_on_across_voussoir_seams() {
        let arch = compute_arch_in_plane(
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(8.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(0.0, 0.0, 1.0),
            Vec3::new(4.0, 0.0, 2.0),
        )
        .unwrap();
        // Voussoir 2 does not start at phase 0: the tile index is carried by the arc length, so the
        // band's texture continues around the arch instead of restarting at every block.
        let later = block_data(&arch, 2, 9);
        let splits = later
            .wedge_faces()
            .iter()
            .filter(|f| f.kind == FaceKind::Inner)
            .filter(|f| f.tile_quads().len() == 2)
            .count();
        assert!(splits > 0, "a mid-arch voussoir should cross a tile edge");
    }

    #[test]
    fn a_quad_straddling_a_tile_boundary_is_split_at_the_boundary() {
        let face = WedgeFace {
            corners: [
                Vec3::new(0.0, 0.0, 0.0),
                Vec3::new(0.0, 0.0, 1.0),
                Vec3::new(1.0, 0.0, 1.0),
                Vec3::new(1.0, 0.0, 0.0),
            ],
            // 2 m of arc: spans tiles 0..1 and 1..2.
            u: [0.5, 2.5, 2.5, 0.5],
            v: [0.0, 0.0, 1.0, 1.0],
            kind: FaceKind::Inner,
        };
        let quads = face.tile_quads();
        assert_eq!(quads.len(), 2);
        // Left part runs to the tile's far edge, right part starts a fresh tile.
        assert_eq!(quads[0].u, [0.5, 1.0, 1.0, 0.5]);
        assert_eq!(quads[1].u, [0.0, 0.5, 0.5, 0.0]);
        // The cut lands at (2.0 - 0.5) / 2.0 = 0.75 of the way along the quad.
        assert!((quads[0].corners[1].z - 0.75).abs() < 1e-12);
        assert!((quads[1].corners[0].z - 0.75).abs() < 1e-12);
        // Both parts keep the face's v mapping.
        assert_eq!(quads[0].v, [0.0, 0.0, 1.0, 1.0]);
        assert_eq!(quads[1].v, [0.0, 0.0, 1.0, 1.0]);
    }

    /// Each face's normal must be perpendicular to the surface it cuts, in the arc's own frame:
    /// curved bands face radially, the two depth faces face along the depth axis, and the radial
    /// joints face along the tangent. Sign is left to the winding, which is the mirror's.
    #[test]
    fn face_normals_sit_on_the_expected_axis() {
        let data = wedge();
        let depth_axis = data.depth().normalize();
        for face in data.wedge_faces() {
            let n = face.outward_normal();
            assert!((n.length() - 1.0).abs() < 1e-12, "{:?} is not a unit normal", face.kind);
            let c = face.corners;
            let along_depth = n.dot(depth_axis).abs();
            // The arc chord of the quad (a tangent direction) and its radial edge.
            let along_chord = n.dot(c[1].sub(c[0]).normalize()).abs();
            let along_radius = n.dot(c[3].sub(c[0]).normalize()).abs();
            match face.kind {
                // Curved bands: perpendicular to the depth axis and to their own arc chord, i.e.
                // radial.
                FaceKind::Inner | FaceKind::Outer => {
                    assert!(along_depth < 1e-6, "{:?} normal leans along depth", face.kind);
                    assert!(along_chord < 1e-6, "{:?} normal is not radial", face.kind);
                }
                // Depth faces: parallel to the depth axis.
                FaceKind::Back | FaceKind::Front => {
                    assert!(along_depth > 0.99, "{:?} normal is not along depth", face.kind);
                }
                // Radial joints: perpendicular to the depth axis and to their radial edge, i.e.
                // tangential.
                FaceKind::Start | FaceKind::End => {
                    assert!(along_depth < 1e-6, "{:?} normal leans along depth", face.kind);
                    assert!(along_radius < 1e-6, "{:?} normal is not tangential", face.kind);
                }
            }
        }
    }

    #[test]
    fn the_inner_and_outer_bands_face_opposite_ways() {
        let faces = wedge().wedge_faces();
        let inner = faces.iter().find(|f| f.kind == FaceKind::Inner).unwrap();
        let outer = faces.iter().find(|f| f.kind == FaceKind::Outer).unwrap();
        assert!(
            inner.outward_normal().dot(outer.outward_normal()) < -0.99,
            "the band's two faces must be wound in opposite directions"
        );
    }

    #[test]
    fn sprite_faces_match_the_java_mapping() {
        assert_eq!(FaceKind::Inner.sprite_face(), Face::Down);
        assert_eq!(FaceKind::Outer.sprite_face(), Face::Up);
        assert_eq!(FaceKind::Back.sprite_face(), Face::North);
    }
}
