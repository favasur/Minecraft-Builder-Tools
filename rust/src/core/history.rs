//! Per-player undo/redo, ported from `server/UndoStore.java`.

use super::ops::BlockChange;
use super::world::World;

/// The Java mod keeps the last 50 block-changing operations per player.
pub const MAX_OPS: usize = 50;

/// A per-player undo/redo history. The Java store keys these by player UUID; the Horizon glue owns
/// one `History` per player.
#[derive(Debug, Default)]
pub struct History {
    undo: Vec<Vec<BlockChange>>,
    redo: Vec<Vec<BlockChange>>,
}

impl History {
    /// Pushes a finished operation. An empty batch is ignored and the redo history is discarded,
    /// exactly as `UndoStore.push` does.
    pub fn push(&mut self, changes: Vec<BlockChange>) {
        if changes.is_empty() {
            return;
        }
        self.undo.push(changes);
        while self.undo.len() > MAX_OPS {
            self.undo.remove(0);
        }
        self.redo.clear();
    }

    /// Restores the last operation. Returns the number of blocks restored, or `None` when empty.
    pub fn undo<W: World>(&mut self, world: &mut W) -> Option<usize> {
        let changes = self.undo.pop()?;
        for change in &changes {
            world.set(change.pos, change.before);
        }
        let count = changes.len();
        self.redo.push(changes);
        Some(count)
    }

    /// Re-applies the last undone operation.
    pub fn redo<W: World>(&mut self, world: &mut W) -> Option<usize> {
        let changes = self.redo.pop()?;
        for change in &changes {
            world.set(change.pos, change.after);
        }
        let count = changes.len();
        self.undo.push(changes);
        Some(count)
    }

    pub fn undo_depth(&self) -> usize {
        self.undo.len()
    }

    pub fn redo_depth(&self) -> usize {
        self.redo.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::pos::{BlockBox, BlockPos};
    use crate::core::world::BlockId;
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
    const DIRT: BlockId = BlockId::new(2);

    #[test]
    fn undo_then_redo_round_trips() {
        let mut world = W::default();
        let mut history = History::default();
        let region = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(1, 0, 0));

        history.push(crate::core::ops::fill(&mut world, region, STONE));
        assert_eq!(world.get(BlockPos::new(0, 0, 0)), STONE);

        assert_eq!(history.undo(&mut world), Some(2));
        assert_eq!(world.get(BlockPos::new(0, 0, 0)), W::AIR);
        assert_eq!(history.redo_depth(), 1);

        assert_eq!(history.redo(&mut world), Some(2));
        assert_eq!(world.get(BlockPos::new(0, 0, 0)), STONE);
    }

    #[test]
    fn pushing_a_new_operation_clears_redo_and_empty_batches_are_ignored() {
        let mut world = W::default();
        let mut history = History::default();
        let one = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(0, 0, 0));

        history.push(crate::core::ops::fill(&mut world, one, STONE));
        history.undo(&mut world);
        assert_eq!(history.redo_depth(), 1);

        history.push(crate::core::ops::fill(&mut world, one, DIRT));
        assert_eq!(history.redo_depth(), 0);

        history.push(Vec::new());
        assert_eq!(history.undo_depth(), 1);
    }

    #[test]
    fn history_keeps_only_the_newest_fifty_operations() {
        let mut world = W::default();
        let mut history = History::default();
        for i in 0..60 {
            let pos = BlockPos::new(i, 0, 0);
            let region = BlockBox::new(pos, pos);
            history.push(crate::core::ops::fill(&mut world, region, STONE));
        }
        assert_eq!(history.undo_depth(), MAX_OPS);
    }
}
