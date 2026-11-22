//! A build plan: the blocks of a finished outline, ordered so a minion can lay them one at a time.
//!
//! Every outline the mod already computes — a wall polygon (`core::wall`), a painted territory
//! (`core::polygon`), a region shell (`core::ops`) — arrives as a `Vec<BlockChange>` with no order in
//! it. A plan puts that batch in the order a builder would work: **band by band**, and within a band
//! bottom-up and row by row. For a wall the band is the course, so the wall rises evenly instead of
//! one tower at a time; for a road it is whatever banding the caller supplies.
//!
//! A plan is also the golem's memory of what is still to come: [`BuildPlan::reserved`] is every
//! position it has not placed yet, which is what stops a fetching golem from mining the very blocks
//! it is about to build.

use super::claim::ClaimStore;
use super::ops::BlockChange;
use super::pos::BlockPos;
use super::world::BlockId;
use std::collections::HashSet;

/// One block to lay: where, what, and which band it belongs to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PlanStep {
    pub pos: BlockPos,
    pub block: BlockId,
    /// The pass the block belongs to. Lower bands are built first; for a wall this is the course.
    pub band: i32,
}

/// The remaining steps of a build, in order, with the positions it will occupy.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct BuildPlan {
    steps: Vec<PlanStep>,
    next: usize,
    reserved: HashSet<BlockPos>,
}

impl BuildPlan {
    /// A plan from explicit steps. Duplicates are dropped (a batch may name a cell twice; building
    /// it twice would spend two blocks for one result).
    pub fn new(steps: Vec<PlanStep>) -> Self {
        let mut plan = Self::default();
        for step in steps {
            plan.push(step);
        }
        plan.sort();
        plan
    }

    /// A plan from a change batch, banded by height: the wall rises course by course.
    pub fn from_changes(changes: &[BlockChange]) -> Self {
        Self::banded(changes, |change| change.pos.y)
    }

    /// A plan from a change batch with the caller's own banding, so a path can be laid segment by
    /// segment and a wall course by course with the same type.
    pub fn banded(changes: &[BlockChange], band: impl Fn(&BlockChange) -> i32) -> Self {
        let mut plan = Self::default();
        for change in changes {
            plan.push(PlanStep {
                pos: change.pos,
                block: change.after,
                band: band(change),
            });
        }
        plan.sort();
        plan
    }

    fn push(&mut self, step: PlanStep) {
        if self.reserved.insert(step.pos) {
            self.steps.push(step);
        }
    }

    fn sort(&mut self) {
        self.steps
            .sort_unstable_by_key(|step| (step.band, step.pos.y, step.pos.z, step.pos.x));
    }

    /// Steps still to lay.
    pub fn remaining(&self) -> usize {
        self.steps.len() - self.next
    }

    /// Steps in the plan, laid or not.
    pub fn len(&self) -> usize {
        self.steps.len()
    }

    pub fn is_empty(&self) -> bool {
        self.steps.is_empty()
    }

    pub fn is_finished(&self) -> bool {
        self.remaining() == 0
    }

    /// The next block to lay, without taking it.
    pub fn peek(&self) -> Option<&PlanStep> {
        self.steps.get(self.next)
    }

    /// Takes the next block, so the plan advances exactly once per block laid.
    pub fn take_next(&mut self) -> Option<PlanStep> {
        let step = self.steps.get(self.next).copied()?;
        self.next += 1;
        self.reserved.remove(&step.pos);
        Some(step)
    }

    /// Every position the plan still intends to fill.
    pub fn reserved(&self) -> &HashSet<BlockPos> {
        &self.reserved
    }

    /// Drops the remaining steps the owner may not build — inside somebody else's claim, or in a
    /// height band that is theirs — and returns how many were dropped.
    ///
    /// This is where a minion inherits its owner's rights: a golem cannot build a wall through
    /// another player's plot, and the sidebar can say how much of the outline was refused rather
    /// than letting the golem stand there waiting forever.
    pub fn skip_unbuildable(&mut self, claims: &ClaimStore, owner: &str) -> usize {
        let mut kept = Vec::with_capacity(self.remaining());
        let mut skipped = 0;
        for step in self.steps.drain(self.next..) {
            if claims.may_build(owner, step.pos.x, step.pos.z, step.pos.y) {
                kept.push(step);
            } else {
                self.reserved.remove(&step.pos);
                skipped += 1;
            }
        }
        self.steps.extend(kept);
        skipped
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::claim::{ClaimRequest, TerritoryKind};
    use crate::core::polygon::Polygon;
    use crate::core::wall::{self, WallSpec};

    const STONE: BlockId = BlockId::new(1);

    fn change(x: i32, y: i32, z: i32, block: BlockId) -> BlockChange {
        BlockChange {
            pos: BlockPos::new(x, y, z),
            before: BlockId::new(0),
            after: block,
        }
    }

    fn square(size: f64) -> Polygon {
        Polygon::new(vec![
            (0.0, 0.0),
            (size, 0.0),
            (size, size),
            (0.0, size),
        ])
        .unwrap()
    }

    /// A world that only records what was written.
    #[derive(Default)]
    struct W {
        cells: std::collections::HashMap<BlockPos, BlockId>,
    }

    impl crate::core::world::World for W {
        const AIR: BlockId = BlockId::new(0);
        fn get(&self, pos: BlockPos) -> BlockId {
            *self.cells.get(&pos).unwrap_or(&Self::AIR)
        }
        fn set(&mut self, pos: BlockPos, id: BlockId) {
            self.cells.insert(pos, id);
        }
    }

    #[test]
    fn a_plan_rises_band_by_band_bottom_up() {
        let mut world = W::default();
        let changes = wall::build(&mut world, &square(6.0), &WallSpec::new(STONE, 3), 64);
        let plan = BuildPlan::from_changes(&changes);

        assert_eq!(plan.len(), changes.len(), "every cell is planned once");
        assert_eq!(plan.remaining(), plan.len());
        assert!(!plan.is_finished());

        let mut bands = Vec::new();
        let mut plan = plan;
        while let Some(step) = plan.take_next() {
            bands.push(step.band);
        }
        assert!(plan.is_finished());
        assert!(bands.windows(2).all(|pair| pair[0] <= pair[1]), "bands must not go back down");
        assert_eq!(*bands.first().unwrap(), 64);
        assert_eq!(*bands.last().unwrap(), 66);
    }

    #[test]
    fn a_caller_can_band_the_plan_itself() {
        let changes = vec![change(0, 0, 0, STONE), change(5, 0, 0, STONE), change(1, 0, 0, STONE)];
        // A road banded by segment: the two far blocks belong to a later pass.
        let plan = BuildPlan::banded(&changes, |c| if c.pos.x >= 5 { 1 } else { 0 });
        let order: Vec<i32> = {
            let mut plan = plan;
            let mut xs = Vec::new();
            while let Some(step) = plan.take_next() {
                xs.push(step.pos.x);
            }
            xs
        };
        assert_eq!(order, vec![0, 1, 5]);
    }

    #[test]
    fn duplicate_cells_are_planned_once() {
        let plan = BuildPlan::from_changes(&[
            change(1, 2, 3, STONE),
            change(1, 2, 3, STONE),
        ]);
        assert_eq!(plan.len(), 1);
        assert_eq!(plan.reserved().len(), 1);
    }

    #[test]
    fn taking_a_step_releases_its_position_from_the_reservation() {
        let mut plan = BuildPlan::from_changes(&[change(0, 0, 0, STONE), change(1, 0, 0, STONE)]);
        let first = plan.take_next().unwrap();
        assert_eq!(first.pos, BlockPos::new(0, 0, 0));
        assert!(!plan.reserved().contains(&BlockPos::new(0, 0, 0)));
        assert!(plan.reserved().contains(&BlockPos::new(1, 0, 0)));
        plan.take_next().unwrap();
        assert!(plan.take_next().is_none());
        assert!(plan.is_finished());
        assert!(plan.reserved().is_empty());
    }

    #[test]
    fn steps_inside_another_players_claim_are_dropped() {
        let mut claims = ClaimStore::new();
        claims
            .claim(ClaimRequest {
                kind: TerritoryKind::Private,
                owner: "bob".to_string(),
                name: "Bob's plot".to_string(),
                polygon: square(10.0),
                vertical: None,
            })
            .unwrap();

        // A wall whose right half runs through Bob's plot.
        let mut world = W::default();
        let line = Polygon::new(vec![(0.0, 0.0), (30.0, 0.0), (30.0, 1.0), (0.0, 1.0)]).unwrap();
        let changes = wall::build(&mut world, &line, &WallSpec::new(STONE, 2), 64);
        let mut plan = BuildPlan::from_changes(&changes);

        let total = plan.remaining();
        let skipped = plan.skip_unbuildable(&claims, "alice");
        assert!(skipped > 0, "the plot straddles the wall");
        assert_eq!(plan.remaining(), total - skipped);
        assert!(
            plan.peek().map(|s| s.pos.x >= 10).unwrap_or(true),
            "the plan resumes at the part alice may build"
        );

        // A plot alice owns over the same ground refuses nothing.
        let mut own = ClaimStore::new();
        own.claim(ClaimRequest {
            kind: TerritoryKind::Private,
            owner: "alice".to_string(),
            name: "Mine".to_string(),
            polygon: square(40.0),
            vertical: None,
        })
        .unwrap();
        let mut again = BuildPlan::from_changes(&changes);
        assert_eq!(again.skip_unbuildable(&own, "alice"), 0);
        assert_eq!(again.remaining(), total);
    }
}
