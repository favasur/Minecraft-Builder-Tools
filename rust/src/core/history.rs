//! Per-player undo/redo, ported from `server/UndoStore.java`.
//!
//! The Java store keeps a `List<BlockChange>` per operation, where a change is a `BlockPos` plus a
//! `BlockState` — 20+ bytes per cell. Fifty operations of a full 32,768-block selection is ~31 MiB
//! of heap per player, which on a server with several builders is a real cost.
//!
//! The port stores each operation packed: one box origin, `u16` offsets from it, and `u16` indices
//! into a tiny palette of the ids involved. That is 10 bytes per cell instead of 20, and undo/redo
//! walk the packed cells directly, so restoring an operation allocates nothing at all.

use super::ops::BlockChange;
use super::pos::BlockPos;
use super::world::{BlockId, World};

/// The Java mod keeps the last 50 block-changing operations per player.
pub const MAX_OPS: usize = 50;

/// One cell of a stored operation, relative to the operation's origin.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Cell {
    dx: u16,
    dy: u16,
    dz: u16,
    before: u16,
    after: u16,
}

/// A stored operation: relative coordinates plus a palette of the ids it touched.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct PackedOp {
    origin: BlockPos,
    palette: Vec<u32>,
    cells: Vec<Cell>,
}

impl PackedOp {
    fn pack(changes: &[BlockChange]) -> Self {
        if changes.is_empty() {
            return Self::default();
        }
        let origin = changes
            .iter()
            .map(|c| c.pos)
            .reduce(|a, b| {
                BlockPos::new(a.x.min(b.x), a.y.min(b.y), a.z.min(b.z))
            })
            .unwrap_or(BlockPos::new(0, 0, 0));

        let mut palette: Vec<u32> = Vec::new();
        let mut intern = |id: BlockId| -> u16 {
            let raw = id.raw();
            if let Some(i) = palette.iter().position(|r| *r == raw) {
                return i as u16;
            }
            palette.push(raw);
            (palette.len() - 1) as u16
        };

        let mut cells = Vec::with_capacity(changes.len());
        for change in changes {
            let before = intern(change.before);
            let after = intern(change.after);
            cells.push(Cell {
                dx: (change.pos.x - origin.x) as u16,
                dy: (change.pos.y - origin.y) as u16,
                dz: (change.pos.z - origin.z) as u16,
                before,
                after,
            });
        }
        Self {
            origin,
            palette,
            cells,
        }
    }

    fn pos(&self, cell: &Cell) -> BlockPos {
        BlockPos::new(
            self.origin.x + cell.dx as i32,
            self.origin.y + cell.dy as i32,
            self.origin.z + cell.dz as i32,
        )
    }

    fn restore<W: World>(&self, world: &mut W, after: bool) {
        for cell in &self.cells {
            let raw = if after { cell.after } else { cell.before };
            let id = BlockId::new(self.palette[raw as usize]);
            world.set(self.pos(cell), id);
        }
    }

    fn len(&self) -> usize {
        self.cells.len()
    }

    /// The heap this costs, against `cells.len() * size_of::<BlockChange>()` for the raw batch.
    fn byte_size(&self) -> usize {
        self.cells.len() * std::mem::size_of::<Cell>() + self.palette.len() * 4
    }
}

/// A per-player undo/redo history. The Java store keys these by player UUID; the Horizon glue owns
/// one `History` per player.
#[derive(Debug, Default)]
pub struct History {
    undo: Vec<PackedOp>,
    redo: Vec<PackedOp>,
}

impl History {
    /// Pushes a finished operation. An empty batch is ignored and the redo history is discarded,
    /// exactly as `UndoStore.push` does.
    pub fn push(&mut self, changes: Vec<BlockChange>) {
        if changes.is_empty() {
            return;
        }
        self.undo.push(PackedOp::pack(&changes));
        while self.undo.len() > MAX_OPS {
            self.undo.remove(0);
        }
        self.redo.clear();
    }

    /// Restores the last operation. Returns the number of blocks restored, or `None` when empty.
    pub fn undo<W: World>(&mut self, world: &mut W) -> Option<usize> {
        let op = self.undo.pop()?;
        op.restore(world, false);
        let count = op.len();
        self.redo.push(op);
        Some(count)
    }

    /// Re-applies the last undone operation.
    pub fn redo<W: World>(&mut self, world: &mut W) -> Option<usize> {
        let op = self.redo.pop()?;
        op.restore(world, true);
        let count = op.len();
        self.undo.push(op);
        Some(count)
    }

    pub fn undo_depth(&self) -> usize {
        self.undo.len()
    }

    pub fn redo_depth(&self) -> usize {
        self.redo.len()
    }

    /// The heap the stored operations cost, for the measurement in `examples/bench.rs`.
    pub fn byte_size(&self) -> usize {
        self.undo
            .iter()
            .chain(self.redo.iter())
            .map(PackedOp::byte_size)
            .sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::ops::fill;
    use crate::core::pos::BlockBox;
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

        history.push(fill(&mut world, region, STONE));
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

        history.push(fill(&mut world, one, STONE));
        history.undo(&mut world);
        assert_eq!(history.redo_depth(), 1);

        history.push(fill(&mut world, one, DIRT));
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
            history.push(fill(&mut world, region, STONE));
        }
        assert_eq!(history.undo_depth(), MAX_OPS);
    }

    #[test]
    fn a_packed_operation_restores_exactly_the_previous_world() {
        let mut world = W::default();
        let region = BlockBox::new(BlockPos::new(-4, -4, -4), BlockPos::new(3, 3, 3));
        // Pre-fill with a two-block pattern so `before` is not uniformly air.
        for pos in region.iter() {
            if (pos.x + pos.y + pos.z) % 2 == 0 {
                world.set(pos, STONE);
            } else {
                world.set(pos, DIRT);
            }
        }
        let mut snapshot: HashMap<BlockPos, BlockId> = HashMap::new();
        for pos in region.iter() {
            snapshot.insert(pos, world.get(pos));
        }

        let mut history = History::default();
        history.push(fill(&mut world, region, DIRT));
        history.undo(&mut world);

        for pos in region.iter() {
            assert_eq!(world.get(pos), snapshot[&pos], "cell {pos:?} not restored");
        }
    }

    #[test]
    fn packing_halves_the_stored_bytes_of_a_full_selection() {
        let mut world = W::default();
        let region = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(31, 31, 31));
        let changes = fill(&mut world, region, STONE);
        let raw = changes.len() * std::mem::size_of::<BlockChange>();

        let mut history = History::default();
        history.push(changes);
        assert_eq!(history.undo_depth(), 1);
        let ratio = raw as f64 / history.byte_size() as f64;
        assert!(ratio > 1.9, "packed {} vs raw {raw} ({ratio:.2}x)", history.byte_size());
    }
}
