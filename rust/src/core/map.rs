//! The map view of every claim: private territory, public knowledge.
//!
//! A claim is private in *build rights*, not in *knowledge*. Java Edition's territory mods (FTB
//! Chunks, Open Parties and Claims) all draw other players' borders: the owner's name is the point,
//! because "you are standing on someone else's land" is what stops a build before it happens. So
//! the feed carries every claim to every player, and the viewer only changes the colour and the
//! `mine` flag — never whether a claim is there.
//!
//! Two things a map or a sidebar needs are kept apart on purpose:
//!
//! * [`feed`] — the data: id, owner, name, outline, vertical band, and whether it is the viewer's.
//! * [`overlay`] — the geometry: every claim's area and ring in one [`Preview`], the viewer's own in
//!   bright yellow, everyone else's in amber, plus how many did not fit in a view's budget.

use super::claim::{ClaimId, ClaimStore, VerticalExtent};
use super::polygon::Polygon;
use super::preview::{self, Preview, PreviewStyle};

/// Fill for a claim that is not the viewer's, `0xAARRGGBB`: amber at 33 % opacity.
pub const OTHER_FILL: u32 = 0x55FF_C000;
/// Outline for a claim that is not the viewer's: dark goldenrod, so it reads as "not yours" next to
/// the bright yellow of your own.
pub const OTHER_OUTLINE: u32 = 0xFFB8_860B;

/// The colours a claim is drawn in: bright yellow for the viewer's own, amber for anyone else's.
pub fn style_for(mine: bool) -> PreviewStyle {
    if mine {
        PreviewStyle::YELLOW
    } else {
        PreviewStyle {
            fill: OTHER_FILL,
            outline: OTHER_OUTLINE,
            width: preview::OUTLINE_WIDTH,
        }
    }
}

/// One claim as a map or a sidebar lists it.
#[derive(Clone, Debug, PartialEq)]
pub struct ClaimMarker {
    pub id: ClaimId,
    pub owner: String,
    pub name: String,
    pub polygon: Polygon,
    pub vertical: VerticalExtent,
    /// Whether the player asking owns it.
    pub mine: bool,
}

impl ClaimMarker {
    /// Ground area in blocks squared.
    pub fn area(&self) -> f64 {
        self.polygon.area().abs()
    }

    /// `(min_x, min_z, max_x, max_z)` for placing the label on a map, clamped so the dense list can
    /// be paged without reading every outline twice.
    pub fn bounds(&self) -> (f64, f64, f64, f64) {
        self.polygon.bounds()
    }

    /// What a map label shows: the claim's name and the player who owns it.
    pub fn label(&self) -> String {
        format!("{} ({})", self.name, self.owner)
    }
}

/// Every claim on the server, with the revision they were read at.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ClaimFeed {
    /// Bumped whenever a claim is made or released, so a client can skip an unchanged list.
    pub revision: u64,
    pub markers: Vec<ClaimMarker>,
}

impl ClaimFeed {
    pub fn len(&self) -> usize {
        self.markers.len()
    }

    pub fn is_empty(&self) -> bool {
        self.markers.is_empty()
    }

    /// The claims the asking player owns.
    pub fn mine(&self) -> impl Iterator<Item = &ClaimMarker> {
        self.markers.iter().filter(|marker| marker.mine)
    }

    /// The claims someone else owns — what a map draws around the player.
    pub fn others(&self) -> impl Iterator<Item = &ClaimMarker> {
        self.markers.iter().filter(|marker| !marker.mine)
    }
}

/// Reads every claim for `viewer`, whoever owns it.
pub fn feed(store: &ClaimStore, viewer: &str) -> ClaimFeed {
    ClaimFeed {
        revision: store.revision(),
        markers: store
            .claims()
            .iter()
            .map(|claim| ClaimMarker {
                id: claim.id,
                owner: claim.owner.clone(),
                name: claim.name.clone(),
                polygon: claim.polygon.clone(),
                vertical: claim.vertical,
                mine: claim.owner == viewer,
            })
            .collect(),
    }
}

/// The overlay geometry for a map: every claim's area and ring, and what did not fit.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct ClaimOverlay {
    pub preview: Preview,
    /// Claims left out because a view's quad or line budget was full. Reported rather than silently
    /// dropped: a map that hides territory must say so.
    pub hidden: usize,
}

/// Draws every claim at `y`, the viewer's own in bright yellow and the rest in amber.
///
/// A dense server can have more claims than one view can take, so each claim is added only while
/// both of the client's budgets still hold; the rest are counted in [`ClaimOverlay::hidden`].
pub fn overlay(store: &ClaimStore, viewer: &str, y: i32) -> ClaimOverlay {
    let mut out = ClaimOverlay::default();
    for claim in store.claims() {
        let piece = preview::polygon_preview(
            &claim.polygon,
            y,
            style_for(claim.owner == viewer),
        );
        if out.preview.quads.len() + piece.quads.len() > preview::QUAD_BUDGET
            || out.preview.lines.len() + piece.lines.len() > preview::LINE_BUDGET
        {
            out.hidden += 1;
            continue;
        }
        out.preview.extend(piece);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::claim::{ClaimError, ClaimRequest, TerritoryKind};

    fn square(x: f64, z: f64, size: f64) -> Polygon {
        Polygon::new(vec![
            (x, z),
            (x + size, z),
            (x + size, z + size),
            (x, z + size),
        ])
        .unwrap()
    }

    fn claim(store: &mut ClaimStore, owner: &str, name: &str, x: f64) {
        store
            .claim(ClaimRequest {
                kind: TerritoryKind::Private,
                owner: owner.to_string(),
                name: name.to_string(),
                polygon: square(x, 0.0, 8.0),
                vertical: None,
            })
            .unwrap();
    }

    #[test]
    fn every_player_sees_every_claim_and_only_ownership_differs() {
        let mut store = ClaimStore::new();
        claim(&mut store, "alice", "Plot", 0.0);
        claim(&mut store, "bob", "Quarry", 20.0);

        let as_alice = feed(&store, "alice");
        let as_bob = feed(&store, "bob");
        let as_carol = feed(&store, "carol");

        // Same claims for everyone: a stranger still sees both, so they know whose land it is.
        assert_eq!(as_alice.len(), 2);
        assert_eq!(as_bob.len(), 2);
        assert_eq!(as_carol.len(), 2);
        assert_eq!(as_carol.mine().count(), 0);
        assert_eq!(as_carol.others().count(), 2);

        // Only the flag and the colour follow the viewer.
        assert_eq!(as_alice.markers[0].label(), "Plot (alice)");
        assert!(as_alice.markers[0].mine);
        assert!(!as_alice.markers[1].mine);
        assert!(as_bob.markers[1].mine);
        assert_eq!(as_carol.revision, as_alice.revision);
    }

    #[test]
    fn the_revision_only_moves_when_the_claim_set_does() {
        let mut store = ClaimStore::new();
        let before = feed(&store, "alice").revision;
        claim(&mut store, "alice", "Plot", 0.0);
        let after_claim = feed(&store, "alice").revision;
        assert_ne!(before, after_claim);

        // A refused claim changes nothing, so it must not look like news to a client.
        let refused = store.claim(ClaimRequest {
            kind: TerritoryKind::Private,
            owner: "bob".to_string(),
            name: "Overlap".to_string(),
            polygon: square(2.0, 2.0, 4.0),
            vertical: None,
        });
        assert!(matches!(refused, Err(ClaimError::Overlap { .. })));
        assert_eq!(feed(&store, "alice").revision, after_claim);

        store.release(ClaimId(0));
        assert_ne!(feed(&store, "alice").revision, after_claim);
        assert!(feed(&store, "alice").is_empty());
    }

    #[test]
    fn the_overlay_draws_your_claims_and_everyone_elses() {
        let mut store = ClaimStore::new();
        claim(&mut store, "alice", "Mine", 0.0);
        claim(&mut store, "bob", "Theirs", 20.0);

        let overlay = overlay(&store, "alice", 63);
        assert_eq!(overlay.hidden, 0);
        assert!(overlay.preview.fits_budget());
        // Two squares: eight outline lines, and a fill quad per covered row each.
        assert_eq!(overlay.preview.lines.len(), 8);

        let mine = style_for(true);
        let theirs = style_for(false);
        assert!(
            overlay.preview.quads.iter().any(|q| q.color == mine.fill),
            "the viewer's own claim keeps the bright yellow fill"
        );
        assert!(
            overlay.preview.quads.iter().any(|q| q.color == theirs.fill),
            "someone else's claim is still drawn, in amber"
        );
        assert_ne!(mine.fill, theirs.fill);
        assert_ne!(mine.outline, theirs.outline);
        assert_eq!(
            overlay.preview.lines.iter().filter(|l| l.color == theirs.outline).count(),
            4
        );
    }

    #[test]
    fn a_stranger_gets_the_same_overlay_geometry_as_the_owner() {
        let mut store = ClaimStore::new();
        claim(&mut store, "alice", "Plot", 0.0);

        let owner_view = overlay(&store, "alice", 63);
        let stranger_view = overlay(&store, "carol", 63);
        assert_eq!(
            owner_view.preview.quads.len(),
            stranger_view.preview.quads.len()
        );
        assert_eq!(
            owner_view.preview.lines.len(),
            stranger_view.preview.lines.len()
        );
        // Only the colours differ.
        assert_ne!(owner_view.preview.quads[0].color, stranger_view.preview.quads[0].color);
    }

    #[test]
    fn a_marker_reports_its_area_and_bounds_for_a_label() {
        let mut store = ClaimStore::new();
        claim(&mut store, "alice", "Plot", 4.0);
        let marker = &feed(&store, "bob").markers[0];
        assert!((marker.area() - 64.0).abs() < 1.0e-9);
        assert_eq!(marker.bounds(), (4.0, 0.0, 12.0, 8.0));
        assert!(marker.vertical.is_full());
    }
}
