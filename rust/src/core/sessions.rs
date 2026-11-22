//! Per-player state: whose stakes are whose, whose golems are whose, and what each may claim.
//!
//! The tools all work from a survey and a wall spec, and in a server those belong to a player rather
//! than to the mod. This module is that ownership: a map from the player's UUID to everything they
//! have planted, the settings they last chose, the golems they have out, plus the shared claim store
//! the players' claims go into. The glue's job is only to pass the UUID in and act on what comes
//! back, so no hook has to keep per-player state itself.
//!
//! Claims stay in one [`ClaimStore`] for the whole server, because a claim is private in build rights
//! and not in knowledge: every player must be able to see every claim. What is per player is the
//! right to release one, and the survey it comes from.

use std::collections::HashMap;

use super::claim::{
    ClaimError, ClaimId, ClaimRequest, ClaimStore, TerritoryKind, VerticalExtent,
};
use super::edit::BlockEdit;
use super::golem::{Golem, GolemAction, GolemKind, GolemRules};
use super::ops::BlockChange;
use super::plan::BuildPlan;
use super::pos::BlockPos;
use super::settings::BuilderSettings;
use super::survey::{Stake, Survey};
use super::wall::Housing;
use super::world::{BlockId, World};

/// A player's identity, as the game gives it in a click or an entity.
pub type PlayerId = u128;

/// Everything one player has on the go.
#[derive(Clone, Debug, PartialEq)]
pub struct PlayerSession {
    /// The name the game shows: the owner written on claims and golem origins.
    pub owner: String,
    /// Linear and territorial stakes: spans, angles and territories.
    pub survey: Survey,
    /// The housing tool's stakes, kept apart so a wall survey does not disturb a path survey.
    pub housing: Housing,
    /// The creative panel's values, which also seed the housing spec.
    pub settings: BuilderSettings,
    golems: Vec<Golem>,
}

impl PlayerSession {
    /// A fresh session whose walls are built from `wall_block`.
    pub fn new(owner: impl Into<String>, wall_block: BlockId) -> Self {
        let settings = BuilderSettings::DEFAULT;
        Self {
            owner: owner.into(),
            survey: Survey::new(),
            housing: Housing::new(settings.wall_spec(wall_block)),
            settings,
            golems: Vec::new(),
        }
    }

    /// Takes the panel's height, thickness and batter into the housing spec, so Enter builds what
    /// the sidebar shows.
    pub fn sync_housing(&mut self) {
        let spec = self.settings.wall_spec(self.housing.spec().block);
        *self.housing.spec_mut() = spec;
    }

    /// The golems this player has out, in the order they were spawned.
    pub fn golems(&self) -> &[Golem] {
        &self.golems
    }

    pub fn golem(&self, id: u64) -> Option<&Golem> {
        self.golems.iter().find(|golem| golem.id == id)
    }

    pub fn golem_mut(&mut self, id: u64) -> Option<&mut Golem> {
        self.golems.iter_mut().find(|golem| golem.id == id)
    }

    /// Adds a golem this player spawned: always a minion, and always theirs.
    fn add_golem(&mut self, id: u64, kind: GolemKind, pos: BlockPos) -> &Golem {
        self.golems
            .push(Golem::spawned_by_player(id, kind, self.owner.clone(), pos));
        self.golems.last().expect("just pushed")
    }

    /// Sends a golem away. `false` when it was not this player's.
    pub fn dismiss_golem(&mut self, id: u64) -> bool {
        let before = self.golems.len();
        self.golems.retain(|golem| golem.id != id);
        self.golems.len() != before
    }

    /// Gives a golem an outline to lay. Refused golems are not this player's.
    pub fn assign(&mut self, id: u64, plan: BuildPlan, claims: &ClaimStore) -> Option<usize> {
        self.golem_mut(id)?.assign(plan, claims)
    }
}

/// Every player's session, and the claims they all share.
#[derive(Clone, Debug, Default)]
pub struct Sessions {
    players: HashMap<PlayerId, PlayerSession>,
    claims: ClaimStore,
    next_golem: u64,
    wall_block: BlockId,
}

impl Sessions {
    /// Sessions whose new housing tools build walls from `wall_block`.
    pub fn new(wall_block: BlockId) -> Self {
        Self {
            players: HashMap::new(),
            claims: ClaimStore::new(),
            next_golem: 1,
            wall_block,
        }
    }

    pub fn wall_block(&self) -> BlockId {
        self.wall_block
    }

    pub fn session(&self, player: PlayerId) -> Option<&PlayerSession> {
        self.players.get(&player)
    }

    pub fn session_mut(&mut self, player: PlayerId) -> Option<&mut PlayerSession> {
        self.players.get_mut(&player)
    }

    /// The player's session, created the first time they plant something. A player who is renamed
    /// keeps the name they arrived with, so their old claims stay theirs.
    pub fn open(&mut self, player: PlayerId, owner: impl Into<String>) -> &mut PlayerSession {
        let wall_block = self.wall_block;
        self.players
            .entry(player)
            .or_insert_with(|| PlayerSession::new(owner, wall_block))
    }

    /// Forgets a player who left, leaving their golems out: the caller decides whether to despawn
    /// them first. Claims are not forgotten, since they belong to the place.
    pub fn close(&mut self, player: PlayerId) -> Option<PlayerSession> {
        self.players.remove(&player)
    }

    pub fn len(&self) -> usize {
        self.players.len()
    }

    pub fn is_empty(&self) -> bool {
        self.players.is_empty()
    }

    pub fn players(&self) -> impl Iterator<Item = (PlayerId, &PlayerSession)> {
        self.players.iter().map(|(id, session)| (*id, session))
    }

    pub fn claims(&self) -> &ClaimStore {
        &self.claims
    }

    /// The shared store, for the hooks that only read build rights.
    pub fn claims_mut(&mut self) -> &mut ClaimStore {
        &mut self.claims
    }

    /// Plants a linear or territorial stake for one player. `None` when their survey is full.
    pub fn plant(&mut self, player: PlayerId, owner: impl Into<String>, pos: BlockPos) -> Option<Stake> {
        let session = self.open(player, owner);
        session.survey.plant(pos).cloned()
    }

    /// Plants a housing stake, which the wall tool reads.
    pub fn plant_housing(
        &mut self,
        player: PlayerId,
        owner: impl Into<String>,
        pos: BlockPos,
    ) -> Option<Stake> {
        let session = self.open(player, owner);
        session.housing.plant(pos).cloned()
    }

    /// Takes back the player's last stake of either kind: housing stakes first when their tool is
    /// the one in hand, which is the order the toolbar shows.
    pub fn unplant(&mut self, player: PlayerId, housing: bool) -> Option<Stake> {
        let session = self.players.get_mut(&player)?;
        if housing {
            session.housing.unplant()
        } else {
            session.survey.unplant()
        }
    }

    /// The Enter key on a housing survey: raises the wall and hands back the changes.
    pub fn enter_housing<W: World>(
        &mut self,
        player: PlayerId,
        world: &mut W,
    ) -> Option<(Vec<BlockChange>, BlockEdit)> {
        let session = self.players.get_mut(&player)?;
        session.sync_housing();
        session.housing.enter(world)
    }

    /// Claims the area the player's housing stakes enclose, on their behalf.
    ///
    /// `None` when the stakes do not enclose an area; otherwise the store's answer, which refuses an
    /// overlap with someone else's claim.
    pub fn claim_area(
        &mut self,
        player: PlayerId,
        kind: TerritoryKind,
        name: impl Into<String>,
        vertical: Option<VerticalExtent>,
    ) -> Option<Result<ClaimId, ClaimError>> {
        let session = self.players.get(&player)?;
        let polygon = session.housing.polygon()?;
        let request = ClaimRequest {
            kind,
            owner: session.owner.clone(),
            name: name.into(),
            polygon,
            vertical,
        };
        Some(self.claims.claim(request))
    }

    /// Releases a claim, but only one this player owns: a claim's owner is not a suggestion.
    pub fn release_claim(&mut self, player: PlayerId, claim: ClaimId) -> bool {
        let Some(session) = self.players.get(&player) else {
            return false;
        };
        if self.claims.get(claim).is_none_or(|held| held.owner != session.owner) {
            return false;
        }
        self.claims.release(claim)
    }

    /// How many claims the player holds, for the panel's tally.
    pub fn claims_held(&self, player: PlayerId) -> usize {
        self.players
            .get(&player)
            .map(|session| self.claims.owned_by(&session.owner).len())
            .unwrap_or(0)
    }

    /// Spawns a golem for a player: a minion, carrying their name.
    pub fn spawn_golem(
        &mut self,
        player: PlayerId,
        owner: impl Into<String>,
        kind: GolemKind,
        pos: BlockPos,
    ) -> u64 {
        let id = self.next_golem;
        self.next_golem += 1;
        self.open(player, owner).add_golem(id, kind, pos);
        id
    }

    /// One tick of every golem the player has out, in spawn order.
    pub fn tick_golems<W: World>(
        &mut self,
        player: PlayerId,
        world: &mut W,
        rules: GolemRules,
    ) -> Vec<(u64, GolemAction)> {
        let claims = &self.claims;
        let Some(session) = self.players.get_mut(&player) else {
            return Vec::new();
        };
        session
            .golems
            .iter_mut()
            .map(|golem| (golem.id, golem.tick(world, claims, rules)))
            .collect()
    }

    /// Every player's golems, tagged with their owner, for a level tick that does not know which
    /// player is online.
    pub fn tick_all<W: World>(
        &mut self,
        world: &mut W,
        rules: GolemRules,
    ) -> Vec<(PlayerId, u64, GolemAction)> {
        let claims = &self.claims;
        let mut out = Vec::new();
        for (player, session) in self.players.iter_mut() {
            for golem in session.golems.iter_mut() {
                out.push((*player, golem.id, golem.tick(world, claims, rules)));
            }
        }
        out
    }

    /// The yellow preview the owner of a survey sees: their housing outline while stakes are down.
    pub fn housing_preview(&self, player: PlayerId) -> Option<super::preview::Preview> {
        self.players
            .get(&player)
            .map(|session| session.housing.preview())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[derive(Default)]
    struct W {
        cells: HashMap<BlockPos, BlockId>,
    }

    impl World for W {
        const AIR: BlockId = BlockId::new(0);
        fn get(&self, pos: BlockPos) -> BlockId {
            *self.cells.get(&pos).unwrap_or(&Self::AIR)
        }
        fn set(&mut self, pos: BlockPos, id: BlockId) {
            self.cells.insert(pos, id);
        }
    }

    const STONE: BlockId = BlockId::new(1);
    const PLAYER_A: PlayerId = 0xA;
    const PLAYER_B: PlayerId = 0xB;

    fn pos(x: i32, y: i32, z: i32) -> BlockPos {
        BlockPos::new(x, y, z)
    }

    #[test]
    fn stakes_belong_to_the_player_who_planted_them() {
        let mut sessions = Sessions::new(STONE);
        sessions.plant(PLAYER_A, "Ana", pos(0, 64, 0));
        sessions.plant(PLAYER_A, "Ana", pos(10, 64, 0));
        assert_eq!(sessions.session(PLAYER_A).unwrap().survey.len(), 2);
        assert!(sessions.session(PLAYER_B).is_none(), "B has planted nothing yet");

        sessions.plant(PLAYER_B, "Bo", pos(0, 64, 5));
        assert_eq!(sessions.session(PLAYER_B).unwrap().survey.len(), 1);
        assert_eq!(sessions.session(PLAYER_A).unwrap().survey.len(), 2);
        assert_eq!(sessions.len(), 2);
    }

    #[test]
    fn opening_a_session_twice_keeps_what_was_there() {
        let mut sessions = Sessions::new(STONE);
        sessions.plant(PLAYER_A, "Ana", pos(0, 64, 0));
        sessions.session_mut(PLAYER_A).unwrap().settings.wall_height = 9;

        sessions.open(PLAYER_A, "Ana");
        let session = sessions.session(PLAYER_A).unwrap();
        assert_eq!(session.survey.len(), 1);
        assert_eq!(session.settings.wall_height, 9);
        assert_eq!(sessions.len(), 1, "still one player, not two");
    }

    #[test]
    fn the_survey_and_the_housing_tool_do_not_overwrite_each_other() {
        let mut sessions = Sessions::new(STONE);
        sessions.plant(PLAYER_A, "Ana", pos(0, 64, 0));
        sessions.plant_housing(PLAYER_A, "Ana", pos(4, 64, 0));
        sessions.plant_housing(PLAYER_A, "Ana", pos(4, 64, 4));
        let session = sessions.session(PLAYER_A).unwrap();
        assert_eq!(session.survey.len(), 1);
        assert_eq!(session.housing.len(), 2);

        sessions.unplant(PLAYER_A, false);
        let session = sessions.session(PLAYER_A).unwrap();
        assert!(session.survey.is_empty());
        assert_eq!(session.housing.len(), 2);
    }

    #[test]
    fn gamerule_values_and_settings_reach_the_housing_spec() {
        let mut sessions = Sessions::new(STONE);
        let session = sessions.open(PLAYER_A, "Ana");
        session.settings.wall_height = 7;
        session.settings.wall_thickness = 1.5;
        session.settings.wall_batter = 0.4;
        session.sync_housing();
        let spec = sessions.session(PLAYER_A).unwrap().housing.spec();
        assert_eq!(spec.height, 7);
        assert!((spec.thickness - 1.5).abs() < 1.0e-9);
        assert!((spec.batter - 0.4).abs() < 1.0e-9);
        assert_eq!(spec.block, STONE);
    }

    #[test]
    fn enter_builds_only_the_players_own_wall() {
        let mut sessions = Sessions::new(STONE);
        let mut world = W::default();
        for stake in [pos(0, 64, 0), pos(4, 64, 0), pos(4, 64, 4)] {
            sessions.plant_housing(PLAYER_A, "Ana", stake);
        }
        let (changes, edit) = sessions.enter_housing(PLAYER_A, &mut world).unwrap();
        assert!(!changes.is_empty());
        assert!(!edit.runs.is_empty());
        assert!(!world.cells.is_empty(), "the wall is in the world");

        assert!(
            sessions.enter_housing(PLAYER_B, &mut world).is_none(),
            "a player with no stakes has nothing to build"
        );
    }

    #[test]
    fn a_claim_comes_from_the_players_polygon_and_only_they_can_release_it() {
        let mut sessions = Sessions::new(STONE);
        for stake in [pos(0, 64, 0), pos(20, 64, 0), pos(20, 64, 20), pos(0, 64, 20)] {
            sessions.plant_housing(PLAYER_A, "Ana", stake);
        }
        sessions.open(PLAYER_B, "Bo");
        let claim = sessions
            .claim_area(PLAYER_A, TerritoryKind::Private, "Home", None)
            .expect("stakes enclose an area")
            .expect("an empty store accepts it");

        assert_eq!(sessions.claims().len(), 1);
        assert_eq!(sessions.claims_held(PLAYER_A), 1);
        assert_eq!(sessions.claims_held(PLAYER_B), 0);
        assert_eq!(sessions.claims().get(claim).unwrap().owner, "Ana");

        assert!(!sessions.release_claim(PLAYER_B, claim), "not Bo's claim");
        assert!(sessions.claims().get(claim).is_some());
        assert!(sessions.release_claim(PLAYER_A, claim));
        assert!(sessions.claims().is_empty());
    }

    #[test]
    fn a_player_without_stakes_claims_nothing() {
        let mut sessions = Sessions::new(STONE);
        sessions.open(PLAYER_A, "Ana");
        assert!(
            sessions
                .claim_area(PLAYER_A, TerritoryKind::Private, "Home", None)
                .is_none()
        );
        assert!(sessions.claims().is_empty());
    }

    #[test]
    fn golems_are_player_spawned_and_numbered_apart() {
        let mut sessions = Sessions::new(STONE);
        let first = sessions.spawn_golem(PLAYER_A, "Ana", GolemKind::Iron, pos(0, 64, 0));
        let second = sessions.spawn_golem(PLAYER_B, "Bo", GolemKind::Copper, pos(5, 64, 5));
        assert_ne!(first, second);

        let minion = &sessions.session(PLAYER_A).unwrap().golems()[0];
        assert!(minion.is_minion());
        assert_eq!(minion.owner(), Some("Ana"));
        assert_eq!(minion.kind, GolemKind::Iron);
        assert!(
            sessions.session(PLAYER_B).unwrap().golem(first).is_none(),
            "one player's golem is not another's"
        );
    }

    #[test]
    fn dismissing_a_golem_is_per_player() {
        let mut sessions = Sessions::new(STONE);
        let mine = sessions.spawn_golem(PLAYER_A, "Ana", GolemKind::Iron, pos(0, 64, 0));
        assert!(!sessions.session_mut(PLAYER_B).map(|s| s.dismiss_golem(mine)).unwrap_or(false));
        assert!(sessions.session_mut(PLAYER_A).unwrap().dismiss_golem(mine));
        assert!(sessions.session(PLAYER_A).unwrap().golems().is_empty());
    }

    #[test]
    fn a_tick_without_a_plan_leaves_every_golem_idle() {
        let mut sessions = Sessions::new(STONE);
        let mut world = W::default();
        sessions.spawn_golem(PLAYER_A, "Ana", GolemKind::Iron, pos(0, 64, 0));
        sessions.spawn_golem(PLAYER_B, "Bo", GolemKind::Copper, pos(2, 64, 2));

        let actions = sessions.tick_all(&mut world, GolemRules::DEFAULT);
        assert_eq!(actions.len(), 2);
        assert!(actions.iter().all(|(_, _, action)| *action == GolemAction::Idle));
        assert!(world.cells.is_empty(), "an idle golem writes nothing");

        let one = sessions.tick_golems(PLAYER_A, &mut world, GolemRules::DEFAULT);
        assert_eq!(one.len(), 1);
        assert_eq!(one[0].1, GolemAction::Idle);
    }

    #[test]
    fn the_housing_preview_follows_the_players_stakes() {
        let mut sessions = Sessions::new(STONE);
        assert!(sessions.housing_preview(PLAYER_A).is_none());
        sessions.open(PLAYER_A, "Ana");
        assert!(sessions.housing_preview(PLAYER_A).unwrap().is_empty());

        for stake in [pos(0, 64, 0), pos(6, 64, 0), pos(6, 64, 6)] {
            sessions.plant_housing(PLAYER_A, "Ana", stake);
        }
        let preview = sessions.housing_preview(PLAYER_A).unwrap();
        assert!(!preview.is_empty(), "three stakes make a yellow outline");
    }

    #[test]
    fn closing_a_session_forgets_the_player_but_not_their_claim() {
        let mut sessions = Sessions::new(STONE);
        for stake in [pos(0, 64, 0), pos(20, 64, 0), pos(20, 64, 20)] {
            sessions.plant_housing(PLAYER_A, "Ana", stake);
        }
        sessions
            .claim_area(PLAYER_A, TerritoryKind::Private, "Home", None)
            .unwrap()
            .unwrap();
        assert!(sessions.close(PLAYER_A).is_some());
        assert!(sessions.session(PLAYER_A).is_none());
        assert_eq!(sessions.claims().len(), 1, "the claim stays with the place");
        assert_eq!(sessions.claims_held(PLAYER_A), 0, "but is no longer counted for them");
    }
}
