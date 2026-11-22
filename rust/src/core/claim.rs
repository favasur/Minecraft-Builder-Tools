//! Territorial claims: private territories with an owner and a vertical extent.
//!
//! A claim is a polygon plus a `startY`/`endY` band. The vertical range is what lets two builders
//! own the same ground at different heights — a surface plot and its own basement — and it is why
//! overlap has to be tested in three dimensions rather than on the map alone: two outlines may
//! cross freely as long as their heights never meet.
//!
//! When no height is given the claim takes the whole world height, so an outline drawn without
//! thinking about Y still protects everything above and below it.

use super::polygon::Polygon;

/// An inclusive vertical band, in block coordinates.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct VerticalExtent {
    pub start_y: i32,
    pub end_y: i32,
}

impl VerticalExtent {
    /// A band between two heights, in either order.
    pub const fn new(a: i32, b: i32) -> Self {
        if a <= b {
            Self {
                start_y: a,
                end_y: b,
            }
        } else {
            Self {
                start_y: b,
                end_y: a,
            }
        }
    }

    /// The whole world height — the default when a builder gives no `startY`/`endY`.
    pub const fn full() -> Self {
        Self {
            start_y: i32::MIN,
            end_y: i32::MAX,
        }
    }

    pub const fn contains(&self, y: i32) -> bool {
        y >= self.start_y && y <= self.end_y
    }

    pub const fn overlaps(&self, other: &Self) -> bool {
        self.start_y <= other.end_y && other.start_y <= self.end_y
    }

    /// How many layers the band covers.
    pub fn height(&self) -> i64 {
        self.end_y as i64 - self.start_y as i64 + 1
    }

    /// Whether this is the unrestricted default.
    pub fn is_full(&self) -> bool {
        *self == Self::full()
    }
}

/// The kinds of territory a builder can mark out. Private is the only one defined so far; more
/// kinds belong here rather than as flags on [`Claim`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TerritoryKind {
    /// Owned by one player: nobody else may build inside it.
    Private,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct ClaimId(pub u64);

/// What a claim request asks for, before it is validated against the existing claims.
#[derive(Clone, Debug, PartialEq)]
pub struct ClaimRequest {
    pub kind: TerritoryKind,
    /// The creator, as the host identifies them (a player UUID or name).
    pub owner: String,
    /// Display name, which the sidebar shows and the owner may change.
    pub name: String,
    pub polygon: Polygon,
    /// `None` takes the whole world height.
    pub vertical: Option<VerticalExtent>,
}

/// A registered claim.
#[derive(Clone, Debug, PartialEq)]
pub struct Claim {
    pub id: ClaimId,
    pub kind: TerritoryKind,
    pub owner: String,
    pub name: String,
    pub polygon: Polygon,
    pub vertical: VerticalExtent,
}

impl Claim {
    /// Ground area in blocks squared.
    pub fn area(&self) -> f64 {
        self.polygon.area().abs()
    }

    /// Volume in blocks cubed — what a quota would be measured in.
    pub fn volume(&self) -> f64 {
        self.area() * self.vertical.height() as f64
    }

    pub fn contains(&self, x: i32, z: i32, y: i32) -> bool {
        self.vertical.contains(y) && self.polygon.contains_cell(x, z)
    }
}

/// Why a claim was refused.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClaimError {
    /// A claim with an empty owner would be nobody's, so it is refused.
    MissingOwner,
    /// The outline crosses `existing` at a height both claims cover.
    Overlap { existing: ClaimId },
}

/// Every claim on the server, in the order they were made.
#[derive(Clone, Debug, Default)]
pub struct ClaimStore {
    claims: Vec<Claim>,
    next_id: u64,
    revision: u64,
}

impl ClaimStore {
    pub fn new() -> Self {
        Self::default()
    }

    /// Claims the territory, or refuses it because it collides with `existing` in a shared height.
    ///
    /// Overlap is a real 3D test: the vertical bands must intersect *and* the outlines must
    /// intersect. Touching along a shared border is not an overlap, so neighbours can claim up to
    /// the same line.
    pub fn claim(&mut self, request: ClaimRequest) -> Result<ClaimId, ClaimError> {
        if request.owner.trim().is_empty() {
            return Err(ClaimError::MissingOwner);
        }
        let vertical = request.vertical.unwrap_or_else(VerticalExtent::full);
        for existing in &self.claims {
            if existing.vertical.overlaps(&vertical)
                && polygons_intersect(&existing.polygon, &request.polygon)
            {
                return Err(ClaimError::Overlap {
                    existing: existing.id,
                });
            }
        }
        let id = ClaimId(self.next_id);
        self.next_id += 1;
        self.revision += 1;
        self.claims.push(Claim {
            id,
            kind: request.kind,
            owner: request.owner,
            name: request.name,
            polygon: request.polygon,
            vertical,
        });
        Ok(id)
    }

    /// The claim covering a block, if any.
    pub fn at(&self, x: i32, z: i32, y: i32) -> Option<&Claim> {
        self.claims.iter().find(|claim| claim.contains(x, z, y))
    }

    pub fn get(&self, id: ClaimId) -> Option<&Claim> {
        self.claims.iter().find(|claim| claim.id == id)
    }

    /// Every claim a player holds.
    pub fn owned_by(&self, owner: &str) -> Vec<&Claim> {
        self.claims
            .iter()
            .filter(|claim| claim.owner == owner)
            .collect()
    }

    /// Whether `owner` may build at that block: their own claim, or no claim at all.
    pub fn may_build(&self, owner: &str, x: i32, z: i32, y: i32) -> bool {
        match self.at(x, z, y) {
            Some(claim) => claim.owner == owner,
            None => true,
        }
    }

    pub fn release(&mut self, id: ClaimId) -> bool {
        let before = self.claims.len();
        self.claims.retain(|claim| claim.id != id);
        let removed = self.claims.len() != before;
        if removed {
            self.revision += 1;
        }
        removed
    }

    /// Bumped whenever a claim is made or released, so the map feed can tell a client that nothing
    /// changed and the claim list can be sent only when it does.
    pub fn revision(&self) -> u64 {
        self.revision
    }

    pub fn claims(&self) -> &[Claim] {
        &self.claims
    }

    pub fn len(&self) -> usize {
        self.claims.len()
    }

    pub fn is_empty(&self) -> bool {
        self.claims.is_empty()
    }
}

/// Edges of a polygon as `(from, to)` pairs, closing the ring.
fn edges(points: &[(f64, f64)]) -> Vec<((f64, f64), (f64, f64))> {
    let n = points.len();
    (0..n)
        .map(|i| (points[i], points[(i + 1) % n]))
        .collect()
}

fn centroid(points: &[(f64, f64)]) -> (f64, f64) {
    let n = points.len() as f64;
    let sum = points
        .iter()
        .fold((0.0, 0.0), |acc, p| (acc.0 + p.0, acc.1 + p.1));
    (sum.0 / n, sum.1 / n)
}

fn orient(a: (f64, f64), b: (f64, f64), c: (f64, f64)) -> f64 {
    (b.0 - a.0) * (c.1 - a.1) - (b.1 - a.1) * (c.0 - a.0)
}

/// A proper crossing: the segments pass through each other. Touching or collinear overlap is not a
/// crossing, which is what lets neighbours share a border.
fn segments_cross(p: (f64, f64), q: (f64, f64), r: (f64, f64), s: (f64, f64)) -> bool {
    let d1 = orient(r, s, p);
    let d2 = orient(r, s, q);
    let d3 = orient(p, q, r);
    let d4 = orient(p, q, s);
    ((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0))
        && ((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0))
}

/// A point counts as inside only when it is not touching the border.
///
/// This matters for outlines whose vertices sit on whole blocks: a point on the boundary is a
/// *touch*, not an overlap, so neighbours can claim up to a shared line. A ray that runs exactly
/// along another polygon's edge is also the one case plain ray casting answers unreliably, and the
/// distance test sidesteps it instead of special-casing it.
const TOUCH_EPSILON: f64 = 1.0e-6;

fn strictly_inside(polygon: &Polygon, x: f64, z: f64) -> bool {
    polygon.distance_to_edge(x, z) > TOUCH_EPSILON && polygon.contains(x, z)
}

/// Whether two outlines share any ground at all.
///
/// A vertex strictly inside the other, either centroid strictly inside the other, or a proper edge
/// crossing. The centroid case is what catches an outline drawn identically to an existing one,
/// whose vertices all sit exactly on the other's border.
pub fn polygons_intersect(a: &Polygon, b: &Polygon) -> bool {
    if a.points().iter().any(|(x, z)| strictly_inside(b, *x, *z)) {
        return true;
    }
    if b.points().iter().any(|(x, z)| strictly_inside(a, *x, *z)) {
        return true;
    }
    let (ax, az) = centroid(a.points());
    if strictly_inside(b, ax, az) {
        return true;
    }
    let (bx, bz) = centroid(b.points());
    if strictly_inside(a, bx, bz) {
        return true;
    }
    let ea = edges(a.points());
    let eb = edges(b.points());
    ea.iter()
        .any(|(p, q)| eb.iter().any(|(r, s)| segments_cross(*p, *q, *r, *s)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn square(x: f64, z: f64, size: f64) -> Polygon {
        Polygon::new(vec![
            (x, z),
            (x + size, z),
            (x + size, z + size),
            (x, z + size),
        ])
        .unwrap()
    }

    fn request(owner: &str, polygon: Polygon, vertical: Option<VerticalExtent>) -> ClaimRequest {
        ClaimRequest {
            kind: TerritoryKind::Private,
            owner: owner.to_string(),
            name: "Plot".to_string(),
            polygon,
            vertical,
        }
    }

    #[test]
    fn an_unspecified_height_takes_the_whole_world() {
        let mut store = ClaimStore::new();
        let id = store.claim(request("alice", square(0.0, 0.0, 10.0), None)).unwrap();
        let claim = store.get(id).unwrap();
        assert!(claim.vertical.is_full());
        assert_eq!(claim.vertical.start_y, i32::MIN);
        assert_eq!(claim.vertical.end_y, i32::MAX);
        assert!(claim.contains(5, 5, -4096));
        assert!(claim.contains(5, 5, 4096));
    }

    #[test]
    fn a_claim_with_start_and_end_height_only_covers_that_band() {
        let mut store = ClaimStore::new();
        let id = store
            .claim(request(
                "alice",
                square(0.0, 0.0, 10.0),
                Some(VerticalExtent::new(64, 80)),
            ))
            .unwrap();
        let claim = store.get(id).unwrap();
        assert!(claim.contains(5, 5, 64));
        assert!(claim.contains(5, 5, 80));
        assert!(!claim.contains(5, 5, 63));
        assert!(!claim.contains(5, 5, 81));
    }

    /// The headline case: free territory underground beneath a claimed surface plot.
    #[test]
    fn an_underground_claim_coexists_with_one_above_it() {
        let mut store = ClaimStore::new();
        let surface = store
            .claim(request(
                "alice",
                square(0.0, 0.0, 10.0),
                Some(VerticalExtent::new(60, 90)),
            ))
            .unwrap();
        let basement = store.claim(request(
            "bob",
            square(0.0, 0.0, 10.0),
            Some(VerticalExtent::new(-64, 59)),
        ));
        assert!(basement.is_ok(), "a claim below must not conflict: {basement:?}");
        assert_eq!(store.len(), 2);

        // Each owner controls their own band and not the other's.
        assert!(store.may_build("alice", 5, 5, 70));
        assert!(!store.may_build("bob", 5, 5, 70));
        assert!(store.may_build("bob", 5, 5, 10));
        assert!(!store.may_build("alice", 5, 5, 10));
        assert_eq!(store.at(5, 5, 70).unwrap().id, surface);
    }

    #[test]
    fn an_overlapping_claim_in_a_shared_band_is_refused() {
        let mut store = ClaimStore::new();
        let first = store
            .claim(request(
                "alice",
                square(0.0, 0.0, 10.0),
                Some(VerticalExtent::new(0, 100)),
            ))
            .unwrap();
        let second = store.claim(request(
            "bob",
            square(5.0, 5.0, 10.0),
            Some(VerticalExtent::new(50, 150)),
        ));
        assert_eq!(second, Err(ClaimError::Overlap { existing: first }));
        assert_eq!(store.len(), 1, "the refused claim must not be stored");
    }

    #[test]
    fn the_exact_same_outline_is_refused() {
        let mut store = ClaimStore::new();
        store
            .claim(request("alice", square(0.0, 0.0, 10.0), None))
            .unwrap();
        let same = store.claim(request("bob", square(0.0, 0.0, 10.0), None));
        assert!(matches!(same, Err(ClaimError::Overlap { .. })));
    }

    #[test]
    fn a_nested_outline_is_refused_but_a_neighbour_sharing_a_border_is_not() {
        let mut store = ClaimStore::new();
        store
            .claim(request("alice", square(0.0, 0.0, 10.0), None))
            .unwrap();

        let nested = store.claim(request("bob", square(2.0, 2.0, 4.0), None));
        assert!(matches!(nested, Err(ClaimError::Overlap { .. })));

        let neighbour = store.claim(request("bob", square(10.0, 0.0, 10.0), None));
        assert!(neighbour.is_ok(), "a shared border is not an overlap");
        assert_eq!(store.len(), 2);
    }

    #[test]
    fn crossing_outlines_overlap() {
        let mut store = ClaimStore::new();
        // A plus-shaped crossing: no vertex of either lies inside the other.
        let horizontal =
            Polygon::new(vec![(0.0, 4.0), (20.0, 4.0), (20.0, 6.0), (0.0, 6.0)]).unwrap();
        let vertical =
            Polygon::new(vec![(8.0, 0.0), (12.0, 0.0), (12.0, 10.0), (8.0, 10.0)]).unwrap();
        store.claim(request("alice", horizontal, None)).unwrap();
        let crossed = store.claim(request("bob", vertical, None));
        assert!(matches!(crossed, Err(ClaimError::Overlap { .. })));
    }

    #[test]
    fn a_claim_needs_an_owner() {
        let mut store = ClaimStore::new();
        let err = store.claim(request("   ", square(0.0, 0.0, 4.0), None));
        assert_eq!(err, Err(ClaimError::MissingOwner));
        assert!(store.is_empty());
    }

    #[test]
    fn area_and_volume_follow_the_outline_and_the_band() {
        let mut store = ClaimStore::new();
        let id = store
            .claim(request(
                "alice",
                square(0.0, 0.0, 10.0),
                Some(VerticalExtent::new(-10, 10)),
            ))
            .unwrap();
        let claim = store.get(id).unwrap();
        assert!((claim.area() - 100.0).abs() < 1.0e-9);
        // 21 layers between -10 and 10 inclusive.
        assert_eq!(claim.vertical.height(), 21);
        assert!((claim.volume() - 2100.0).abs() < 1.0e-9);
    }

    #[test]
    fn releasing_frees_the_ground_and_ownership_is_queryable() {
        let mut store = ClaimStore::new();
        let a = store.claim(request("alice", square(0.0, 0.0, 10.0), None)).unwrap();
        let b = store.claim(request("alice", square(20.0, 0.0, 10.0), None)).unwrap();
        store.claim(request("bob", square(40.0, 0.0, 10.0), None)).unwrap();

        assert_eq!(store.owned_by("alice").len(), 2);
        assert_eq!(store.owned_by("bob").len(), 1);

        assert!(store.release(a));
        assert!(!store.release(a), "releasing twice must not report success");
        assert_eq!(store.owned_by("alice").len(), 1);
        assert!(store.get(b).is_some());

        // The freed ground is buildable by anyone again.
        assert!(store.at(5, 5, 0).is_none());
        assert!(store.may_build("carol", 5, 5, 0));
    }

    #[test]
    fn a_vertical_band_normalises_its_ends() {
        let extent = VerticalExtent::new(80, 60);
        assert_eq!(extent.start_y, 60);
        assert_eq!(extent.end_y, 80);
        assert!(extent.overlaps(&VerticalExtent::new(50, 70)));
        assert!(!extent.overlaps(&VerticalExtent::new(81, 90)));
    }
}
