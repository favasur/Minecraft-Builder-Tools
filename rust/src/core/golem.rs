//! Iron and Copper Golem minions: a player's outline, laid block by block.
//!
//! A golem is a minion only when a **player** spawned it. A golem the world spawned (a village's
//! iron golem, a structure's) is left alone: it has an origin of [`SpawnOrigin::World`], is not a
//! minion, and [`Golem::tick`] answers [`GolemAction::Refused`] without touching the world. That is
//! the whole ownership rule, and it is data rather than a tag someone has to remember to set.
//!
//! A minion works a [`BuildPlan`] — any outline the mod already computes — and lays **one block per
//! cadence**, in plan order, so a wall rises course by course and a path segment by segment. Iron
//! golems are steady and carry more; copper golems are quicker but range less. Both inherit their
//! owner's claim rights twice over: the plan drops cells their owner may not build
//! ([`BuildPlan::skip_unbuildable`]), and a fetching golem will not mine a block that is inside
//! somebody else's claim.
//!
//! ## `/gamerule GolemFetching`
//!
//! [`GolemRules::fetching`] is the rule, registered as `buildertools:golem_fetching` (game-rule ids
//! are lower-case resource locations; the sidebar and this documentation call it `GolemFetching`).
//!
//! * `true` — the golem physically goes for the block. `tick` answers
//!   [`GolemAction::Fetching`] with the nearest source it found within its reach, the glue walks the
//!   entity there, breaks the block and hands it back with [`Golem::give`]. Nothing is nearby →
//!   [`GolemAction::Waiting`], and the golem stands still rather than conjuring stone.
//! * `false` — the needed block simply appears in the golem's hands
//!   ([`GolemAction::BlockAppeared`]) and is placed on the next tick.

use super::claim::ClaimStore;
use super::ops::BlockChange;
use super::plan::BuildPlan;
use super::pos::BlockPos;
use super::world::{BlockId, World};
use std::collections::HashSet;

/// The game rule's id: `buildertools:golem_fetching`, `GolemFetching` in the sidebar and docs.
pub const GAMERULE: &str = "golem_fetching";
/// The rule's category, which `/gamerule` groups it under.
pub const GAMERULE_CATEGORY: &str = "golems";
/// What the rule is called when a player writes it.
pub const GAMERULE_DISPLAY: &str = "GolemFetching";

/// Which golem a minion is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GolemKind {
    Iron,
    Copper,
}

/// How a kind works. Tuning lives here, in one table, rather than scattered through the tick.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GolemProfile {
    /// Ticks between two placed blocks.
    pub cadence_ticks: u32,
    /// Blocks a golem may hold at once.
    pub carry_capacity: u32,
    /// How far it will look for a block to fetch, in blocks (Chebyshev).
    pub fetch_radius: i32,
}

impl GolemKind {
    pub const fn profile(self) -> GolemProfile {
        match self {
            // Iron: slow and strong, reaches far, carries a stack.
            GolemKind::Iron => GolemProfile {
                cadence_ticks: 10,
                carry_capacity: 8,
                fetch_radius: 16,
            },
            // Copper: quicker, but shorter range and smaller hands.
            GolemKind::Copper => GolemProfile {
                cadence_ticks: 6,
                carry_capacity: 4,
                fetch_radius: 12,
            },
        }
    }

    pub const fn name(self) -> &'static str {
        match self {
            GolemKind::Iron => "iron_golem",
            GolemKind::Copper => "copper_golem",
        }
    }
}

/// Who spawned the golem. Only [`SpawnOrigin::Player`] is a minion.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SpawnOrigin {
    /// A player built the golem — a carved pumpkin on iron blocks, or on copper blocks.
    Player(String),
    /// The world spawned it: a village, a structure, a spawner.
    World,
}

impl SpawnOrigin {
    /// Whether this origin makes the golem a minion.
    pub fn is_player(&self) -> bool {
        matches!(self, SpawnOrigin::Player(_))
    }

    /// The player who owns it, if a player does.
    pub fn owner(&self) -> Option<&str> {
        match self {
            SpawnOrigin::Player(owner) => Some(owner),
            SpawnOrigin::World => None,
        }
    }
}

/// `/gamerule GolemFetching`: whether minions fetch their blocks or have them handed over.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GolemRules {
    pub fetching: bool,
}

impl GolemRules {
    /// Fetching on, which is how the rule is declared.
    pub const DEFAULT: Self = Self { fetching: true };

    /// `false`: blocks appear in the golem's hands.
    pub const HANDED: Self = Self { fetching: false };
}

impl Default for GolemRules {
    fn default() -> Self {
        Self::DEFAULT
    }
}

/// What a golem did on a tick. Everything the glue needs is named, so the entity code is a match.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum GolemAction {
    /// Not a minion (world-spawned): nothing to do, and nothing was written.
    Refused,
    /// No plan, or still waiting out its cadence.
    Idle,
    /// Laid one block.
    Placed { change: BlockChange, remaining: usize },
    /// Wants the block at `target`: walk there, break it, then [`Golem::give`] it back.
    Fetching { target: BlockPos, block: BlockId },
    /// Fetching is off: the block was put in its hands.
    BlockAppeared { block: BlockId },
    /// Fetching is on and nothing suitable is within reach; the golem waits.
    Waiting { block: BlockId },
    /// The whole plan is laid.
    Finished,
}

/// One golem.
#[derive(Clone, Debug, PartialEq)]
pub struct Golem {
    pub id: u64,
    pub kind: GolemKind,
    pub origin: SpawnOrigin,
    pub pos: BlockPos,
    plan: Option<BuildPlan>,
    carried: Vec<(BlockId, u32)>,
    cooldown: u32,
    placed: u64,
}

impl Golem {
    /// A golem a player spawned: a minion.
    pub fn spawned_by_player(id: u64, kind: GolemKind, owner: impl Into<String>, pos: BlockPos) -> Self {
        Self::new(id, kind, SpawnOrigin::Player(owner.into()), pos)
    }

    /// A golem the world spawned: not a minion, and it never builds.
    pub fn spawned_by_world(id: u64, kind: GolemKind, pos: BlockPos) -> Self {
        Self::new(id, kind, SpawnOrigin::World, pos)
    }

    fn new(id: u64, kind: GolemKind, origin: SpawnOrigin, pos: BlockPos) -> Self {
        Self {
            id,
            kind,
            origin,
            pos,
            plan: None,
            carried: Vec::new(),
            cooldown: 0,
            placed: 0,
        }
    }

    /// Whether this golem may build: only a player's golem is a minion.
    pub fn is_minion(&self) -> bool {
        self.origin.is_player()
    }

    pub fn owner(&self) -> Option<&str> {
        self.origin.owner()
    }

    pub fn profile(&self) -> GolemProfile {
        self.kind.profile()
    }

    /// How many blocks this golem has laid since it was spawned.
    pub fn placed(&self) -> u64 {
        self.placed
    }

    /// What it is holding, one entry per block id.
    pub fn carried(&self) -> &[(BlockId, u32)] {
        &self.carried
    }

    /// How many blocks it holds in total.
    pub fn total_carried(&self) -> u32 {
        self.carried.iter().map(|(_, count)| count).sum()
    }

    /// How many of `block` it holds.
    pub fn held(&self, block: BlockId) -> u32 {
        self.carried
            .iter()
            .find(|(id, _)| *id == block)
            .map(|(_, count)| *count)
            .unwrap_or(0)
    }

    /// Puts one block in its hands. `false` when its hands are full.
    pub fn give(&mut self, block: BlockId) -> bool {
        if self.total_carried() >= self.profile().carry_capacity {
            return false;
        }
        match self.carried.iter_mut().find(|(id, _)| *id == block) {
            Some((_, count)) => *count += 1,
            None => self.carried.push((block, 1)),
        }
        true
    }

    fn take_one(&mut self, block: BlockId) {
        if let Some(index) = self.carried.iter().position(|(id, _)| *id == block) {
            self.carried[index].1 -= 1;
            if self.carried[index].1 == 0 {
                self.carried.remove(index);
            }
        }
    }

    pub fn plan(&self) -> Option<&BuildPlan> {
        self.plan.as_ref()
    }

    /// Takes an outline to build, minus the cells its owner may not build. `None` when the golem is
    /// not a minion, in which case the plan is not accepted at all.
    ///
    /// Returns how many steps were refused.
    pub fn assign(&mut self, mut plan: BuildPlan, claims: &ClaimStore) -> Option<usize> {
        let owner = self.origin.owner()?.to_owned();
        let skipped = plan.skip_unbuildable(claims, &owner);
        self.plan = Some(plan);
        Some(skipped)
    }

    /// One tick of work. See [`GolemRules`] for what fetching changes, and [`GolemAction`] for what
    /// the entity code must do about it.
    pub fn tick<W: World>(
        &mut self,
        world: &mut W,
        claims: &ClaimStore,
        rules: GolemRules,
    ) -> GolemAction {
        let Some(owner) = self.origin.owner().map(str::to_owned) else {
            return GolemAction::Refused;
        };
        // A finished plan is reported at once, even while a cadence is still running out.
        match self.plan.as_ref() {
            None => return GolemAction::Idle,
            Some(plan) if plan.is_finished() => return GolemAction::Finished,
            Some(_) => {}
        }
        if self.cooldown > 0 {
            self.cooldown -= 1;
            return GolemAction::Idle;
        }

        let Some(step) = self.plan.as_ref().and_then(|plan| plan.peek().copied()) else {
            return GolemAction::Finished;
        };

        if self.held(step.block) == 0 {
            let profile = self.kind.profile();
            if rules.fetching {
                if self.total_carried() >= profile.carry_capacity {
                    // Hands full of the wrong block: waiting is the honest answer.
                    return GolemAction::Waiting { block: step.block };
                }
                let reserved = self
                    .plan
                    .as_ref()
                    .map(|plan| plan.reserved())
                    .cloned()
                    .unwrap_or_else(HashSet::new);
                return match find_source(
                    world,
                    self.pos,
                    profile.fetch_radius,
                    step.block,
                    &reserved,
                    claims,
                    &owner,
                ) {
                    Some(target) => GolemAction::Fetching {
                        target,
                        block: step.block,
                    },
                    None => GolemAction::Waiting { block: step.block },
                };
            }
            // Fetching is off: the block appears in its hands, and is laid on the next tick.
            if !self.give(step.block) {
                return GolemAction::Waiting { block: step.block };
            }
            return GolemAction::BlockAppeared { block: step.block };
        }

        let remaining = {
            let plan = self.plan.as_mut().expect("a step was just read from the plan");
            plan.take_next();
            plan.remaining()
        };
        let before = world.get(step.pos);
        world.set(step.pos, step.block);
        self.take_one(step.block);
        self.cooldown = self.kind.profile().cadence_ticks;
        self.placed += 1;
        GolemAction::Placed {
            change: BlockChange {
                pos: step.pos,
                before,
                after: step.block,
            },
            remaining,
        }
    }
}

/// The nearest block of `block` a fetching golem may take, within `radius` blocks of `from`.
///
/// Shells are searched nearest first, so the answer is the closest block rather than whichever one
/// a flat scan happened to reach; within a shell the order is fixed, so two identical worlds give
/// the same answer. Cells the plan still intends to fill are skipped — a golem must not mine its own
/// wall — and so is anything inside a claim its owner may not build in.
fn find_source<W: World>(
    world: &W,
    from: BlockPos,
    radius: i32,
    block: BlockId,
    reserved: &HashSet<BlockPos>,
    claims: &ClaimStore,
    owner: &str,
) -> Option<BlockPos> {
    for distance in 1..=radius {
        for dy in -distance..=distance {
            for dz in -distance..=distance {
                for dx in -distance..=distance {
                    if dx.abs() != distance && dy.abs() != distance && dz.abs() != distance {
                        continue;
                    }
                    let pos = BlockPos::new(from.x + dx, from.y + dy, from.z + dz);
                    if reserved.contains(&pos) || world.get(pos) != block {
                        continue;
                    }
                    if !claims.may_build(owner, pos.x, pos.z, pos.y) {
                        continue;
                    }
                    return Some(pos);
                }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::claim::{ClaimRequest, TerritoryKind};
    use crate::core::polygon::Polygon;
    use crate::core::wall::{self, WallSpec};
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
    const OAK: BlockId = BlockId::new(2);

    fn square(size: f64) -> Polygon {
        Polygon::new(vec![
            (0.0, 0.0),
            (size, 0.0),
            (size, size),
            (0.0, size),
        ])
        .unwrap()
    }

    fn wall_plan() -> BuildPlan {
        let mut world = W::default();
        let changes = wall::build(&mut world, &square(6.0), &WallSpec::new(STONE, 2), 64);
        BuildPlan::from_changes(&changes)
    }

    #[test]
    fn only_a_players_golem_is_a_minion() {
        let player = Golem::spawned_by_player(1, GolemKind::Iron, "alice", BlockPos::new(0, 64, 0));
        let world = Golem::spawned_by_world(2, GolemKind::Iron, BlockPos::new(0, 64, 0));
        assert!(player.is_minion());
        assert_eq!(player.owner(), Some("alice"));
        assert!(!world.is_minion());
        assert_eq!(world.owner(), None);

        // A village golem refuses a plan outright and stands still with one.
        let mut world_golem = world;
        let claims = ClaimStore::new();
        assert!(world_golem.assign(wall_plan(), &claims).is_none());
        let mut level = W::default();
        assert_eq!(
            world_golem.tick(&mut level, &claims, GolemRules::DEFAULT),
            GolemAction::Refused
        );
        assert!(level.cells.is_empty());
    }

    #[test]
    fn a_minion_lays_one_block_per_cadence_in_plan_order() {
        let claims = ClaimStore::new();
        let mut golem =
            Golem::spawned_by_player(1, GolemKind::Iron, "alice", BlockPos::new(0, 64, 0));
        golem.assign(wall_plan(), &claims).unwrap();
        let mut world = W::default();

        // Lay the first block, then hand the golem the blocks it needs.
        golem.give(STONE);
        let first = golem.tick(&mut world, &claims, GolemRules::HANDED);
        let GolemAction::Placed { change, remaining } = first else {
            panic!("expected a placement, got {first:?}");
        };
        assert_eq!(change.after, STONE);
        assert_eq!(change.before, BlockId::new(0));
        assert_eq!(remaining, golem.plan().unwrap().remaining());

        // Iron's cadence is 10 ticks: the next ten ticks do nothing.
        for _ in 0..golem.profile().cadence_ticks {
            golem.give(STONE);
            assert_eq!(
                golem.tick(&mut world, &claims, GolemRules::HANDED),
                GolemAction::Idle
            );
        }
        golem.give(STONE);
        assert!(matches!(
            golem.tick(&mut world, &claims, GolemRules::HANDED),
            GolemAction::Placed { .. }
        ));
        assert_eq!(golem.placed(), 2);
    }

    #[test]
    fn copper_is_quicker_and_iron_reaches_further() {
        let iron = GolemKind::Iron.profile();
        let copper = GolemKind::Copper.profile();
        assert!(copper.cadence_ticks < iron.cadence_ticks);
        assert!(copper.carry_capacity < iron.carry_capacity);
        assert!(copper.fetch_radius < iron.fetch_radius);

        let claims = ClaimStore::new();
        let mut copper =
            Golem::spawned_by_player(2, GolemKind::Copper, "alice", BlockPos::new(0, 64, 0));
        copper.assign(wall_plan(), &claims).unwrap();
        let mut world = W::default();
        copper.give(STONE);
        assert!(matches!(
            copper.tick(&mut world, &claims, GolemRules::HANDED),
            GolemAction::Placed { .. }
        ));
        // It is ready again sooner than an iron golem would be.
        for _ in 0..copper.profile().cadence_ticks {
            copper.tick(&mut world, &claims, GolemRules::HANDED);
        }
        copper.give(STONE);
        assert!(matches!(
            copper.tick(&mut world, &claims, GolemRules::HANDED),
            GolemAction::Placed { .. }
        ));
    }

    #[test]
    fn fetching_off_puts_the_block_in_its_hands() {
        let claims = ClaimStore::new();
        let mut golem =
            Golem::spawned_by_player(1, GolemKind::Iron, "alice", BlockPos::new(0, 64, 0));
        golem.assign(wall_plan(), &claims).unwrap();
        let mut world = W::default();

        assert_eq!(
            golem.tick(&mut world, &claims, GolemRules::HANDED),
            GolemAction::BlockAppeared { block: STONE }
        );
        assert_eq!(golem.held(STONE), 1, "the block is in the golem's hands");
        assert!(world.cells.is_empty(), "and nothing was built yet");

        // It is placed on the next tick, and the hands are empty again.
        assert!(matches!(
            golem.tick(&mut world, &claims, GolemRules::HANDED),
            GolemAction::Placed { .. }
        ));
        assert_eq!(golem.held(STONE), 0);
    }

    #[test]
    fn fetching_on_walks_to_the_nearest_source() {
        let claims = ClaimStore::new();
        let mut golem =
            Golem::spawned_by_player(1, GolemKind::Iron, "alice", BlockPos::new(0, 64, 0));
        golem.assign(wall_plan(), &claims).unwrap();
        let mut world = W::default();
        // Two loose stone blocks: the nearer one must be chosen.
        world.set(BlockPos::new(3, 64, 0), STONE);
        world.set(BlockPos::new(1, 64, 1), STONE);

        let action = golem.tick(&mut world, &claims, GolemRules::DEFAULT);
        assert_eq!(
            action,
            GolemAction::Fetching {
                target: BlockPos::new(1, 64, 1),
                block: STONE
            }
        );

        // The glue breaks the block and hands it over; the golem builds on its next tick.
        assert!(golem.give(STONE));
        assert!(matches!(
            golem.tick(&mut world, &claims, GolemRules::DEFAULT),
            GolemAction::Placed { .. }
        ));
    }

    #[test]
    fn fetching_on_waits_rather_than_conjuring_a_block() {
        let claims = ClaimStore::new();
        let mut golem =
            Golem::spawned_by_player(1, GolemKind::Copper, "alice", BlockPos::new(0, 64, 0));
        golem.assign(wall_plan(), &claims).unwrap();
        let mut world = W::default();

        assert_eq!(
            golem.tick(&mut world, &claims, GolemRules::DEFAULT),
            GolemAction::Waiting { block: STONE }
        );
        assert_eq!(golem.total_carried(), 0);
        assert!(world.cells.is_empty());
    }

    #[test]
    fn a_fetching_golem_will_not_mine_another_players_land() {
        let mut claims = ClaimStore::new();
        // Bob's plot is away from the building site, but within the golem's reach.
        claims
            .claim(ClaimRequest {
                kind: TerritoryKind::Private,
                owner: "bob".to_string(),
                name: "Plot".to_string(),
                polygon: Polygon::new(vec![(10.0, 10.0), (14.0, 10.0), (14.0, 14.0), (10.0, 14.0)])
                    .unwrap(),
                vertical: None,
            })
            .unwrap();

        let mut golem =
            Golem::spawned_by_player(1, GolemKind::Iron, "alice", BlockPos::new(0, 64, 0));
        golem.assign(wall_plan(), &claims).unwrap();
        let mut world = W::default();
        world.set(BlockPos::new(12, 64, 12), STONE); // inside bob's plot
        assert_eq!(
            golem.tick(&mut world, &claims, GolemRules::DEFAULT),
            GolemAction::Waiting { block: STONE },
            "another player's blocks are not alice's to mine"
        );

        // A block outside the claim, in the same reach, is fair game.
        world.set(BlockPos::new(14, 64, 0), STONE);
        assert_eq!(
            golem.tick(&mut world, &claims, GolemRules::DEFAULT),
            GolemAction::Fetching {
                target: BlockPos::new(14, 64, 0),
                block: STONE
            }
        );
    }

    #[test]
    fn a_plan_is_trimmed_to_what_the_owner_may_build() {
        let mut claims = ClaimStore::new();
        claims
            .claim(ClaimRequest {
                kind: TerritoryKind::Private,
                owner: "bob".to_string(),
                name: "Plot".to_string(),
                polygon: square(100.0),
                vertical: None,
            })
            .unwrap();

        let mut golem =
            Golem::spawned_by_player(1, GolemKind::Iron, "alice", BlockPos::new(0, 64, 0));
        let plan = wall_plan();
        let total = plan.len();
        let skipped = golem.assign(plan, &claims).unwrap();
        assert_eq!(skipped, total, "the whole outline is inside bob's claim");
        assert!(golem.plan().unwrap().is_finished());
        assert_eq!(
            golem.tick(&mut W::default(), &claims, GolemRules::HANDED),
            GolemAction::Finished
        );
    }

    #[test]
    fn hands_hold_only_so_much() {
        let mut golem =
            Golem::spawned_by_player(1, GolemKind::Copper, "alice", BlockPos::new(0, 64, 0));
        let capacity = golem.profile().carry_capacity;
        for _ in 0..capacity {
            assert!(golem.give(OAK));
        }
        assert!(!golem.give(OAK), "hands are full");
        assert_eq!(golem.total_carried(), capacity);
        assert_eq!(golem.held(OAK), capacity);
        // A different block does not sneak in either.
        assert!(!golem.give(STONE));
    }

    #[test]
    fn a_finished_plan_answer_is_finished() {
        let claims = ClaimStore::new();
        let mut golem =
            Golem::spawned_by_player(1, GolemKind::Iron, "alice", BlockPos::new(0, 64, 0));
        golem.assign(wall_plan(), &claims).unwrap();
        let mut world = W::default();

        // Lay the whole wall: hand it a block and wait out each cadence.
        let total = golem.plan().unwrap().len();
        for _ in 0..total {
            golem.give(STONE);
            loop {
                match golem.tick(&mut world, &claims, GolemRules::HANDED) {
                    GolemAction::Placed { .. } => break,
                    GolemAction::Idle | GolemAction::BlockAppeared { .. } => continue,
                    other => panic!("unexpected {other:?}"),
                }
            }
        }
        assert_eq!(golem.placed(), total as u64);
        assert_eq!(
            golem.tick(&mut world, &claims, GolemRules::HANDED),
            GolemAction::Finished
        );
        assert_eq!(world.cells.len(), total, "every planned cell was written once");
    }
}
