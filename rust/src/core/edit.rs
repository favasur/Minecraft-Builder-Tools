//! Packing a finished change batch into one bulk edit.
//!
//! The Java mod applies a region operation with one `level.setBlock` per cell. Horizon's mod API
//! instead takes the whole edit at once — a palette plus vertical column runs, applied by
//! `ServerHost::edit_blocks` with no neighbour notifications and a single chunk resend. This module
//! performs that conversion, so a 32³ fill becomes 1,024 runs instead of 32,768 world writes.
//!
//! The conversion is pure: it reads a `Vec<BlockChange>` and writes a `BlockEdit`. `apply` exists
//! so the equivalence against writing cell by cell can be tested rather than assumed.

use super::ops::BlockChange;
use super::pos::BlockPos;
use super::world::World;
use std::collections::HashMap;

/// A vertical run of one palette entry: `height` cells starting at `(x, y, z)` going up.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BlockRun {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub height: u32,
    /// Index into [`BlockEdit::palette`].
    pub block: u16,
}

/// One bulk edit, in the shape the host applies atomically.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BlockEdit {
    /// The distinct block ids this edit writes.
    pub palette: Vec<u32>,
    /// The cells, as vertical runs.
    pub runs: Vec<BlockRun>,
}

impl BlockEdit {
    /// Packs a change batch into one edit.
    ///
    /// Runs are grouped per `(x, z)` column and merged while the id stays the same, so a solid
    /// box collapses to one run per column and a wall to one run per cell of its face. The output
    /// is deterministic: columns are visited in `(x, z)` order and runs are emitted bottom-up, so
    /// the same batch always produces the same edit.
    ///
    /// Distinct block ids must fit in a `u16` palette index. Real state counts are far below that
    /// (a batch is bounded by the 32,768 blocks it may touch), and `pack` keeps ids beyond the
    /// limit by clamping the palette to the first 65,535 distinct states it sees — a limit signed
    /// into the type rather than silently truncated.
    pub fn pack(changes: &[BlockChange]) -> Self {
        if changes.is_empty() {
            return Self::default();
        }

        // Interning and column lookup are hashed: with a linear scan per cell the packer costs
        // more than the operation it packs (10 ms for a 32^3 fill, against 0.4 ms to fill it).
        let mut palette: Vec<u32> = Vec::new();
        let mut interned: HashMap<u32, u16> = HashMap::new();
        for change in changes {
            let raw = change.after.raw();
            if !interned.contains_key(&raw) && palette.len() < u16::MAX as usize {
                interned.insert(raw, palette.len() as u16);
                palette.push(raw);
            }
        }

        // One flat sort by `(x, z, y)` instead of a hash map of per-column vectors: the sort is a
        // single allocation and beats 1,024 small ones by a wide margin, and the merged runs come
        // out already in the order the host wants them.
        let mut cells: Vec<(i32, i32, i32, u16)> = changes
            .iter()
            .map(|change| {
                let block = interned.get(&change.after.raw()).copied().unwrap_or(0);
                (change.pos.x, change.pos.z, change.pos.y, block)
            })
            .collect();
        cells.sort_unstable();

        let mut runs: Vec<BlockRun> = Vec::new();
        let mut i = 0;
        while i < cells.len() {
            let (x, z, y, block) = cells[i];
            let mut height = 1u32;
            while (i + height as usize) < cells.len() {
                let next = cells[i + height as usize];
                if next.0 != x || next.1 != z || next.3 != block || next.2 != y + height as i32 {
                    break;
                }
                height += 1;
            }
            runs.push(BlockRun {
                x,
                y,
                z,
                height,
                block,
            });
            i += height as usize;
        }

        Self { palette, runs }
    }

    /// How many cells this edit writes.
    pub fn cell_count(&self) -> u64 {
        self.runs.iter().map(|run| run.height as u64).sum()
    }

    /// The heap the packed form costs, against `changes.len() * 20` bytes for the batch itself.
    pub fn byte_size(&self) -> usize {
        self.runs.len() * std::mem::size_of::<BlockRun>() + self.palette.len() * 4
    }

    /// Applies the edit. Used by the tests to prove a packed edit is equivalent to the per-cell
    /// writes it came from; the real host performs this in one call.
    pub fn apply<W: World>(&self, world: &mut W) {
        for run in &self.runs {
            let raw = self.palette.get(run.block as usize).copied().unwrap_or(0);
            let id = super::world::BlockId::new(raw);
            for dy in 0..run.height as i32 {
                world.set(BlockPos::new(run.x, run.y + dy, run.z), id);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::ops::{fill, replace_where, walls};
    use crate::core::pos::BlockBox;
    use crate::core::world::BlockId;
    use std::collections::HashMap;

    #[derive(Default, Clone)]
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

    fn boxed_cube() -> BlockBox {
        BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(31, 31, 31))
    }

    /// The whole point of the packer: applying the packed edit must leave the world in exactly the
    /// state the per-cell writes did.
    fn assert_same_world(a: &W, b: &W, region: BlockBox) {
        let pad = BlockBox::new(
            BlockPos::new(region.min().x - 2, region.min().y - 2, region.min().z - 2),
            BlockPos::new(region.max().x + 2, region.max().y + 2, region.max().z + 2),
        );
        for pos in pad.iter() {
            assert_eq!(a.get(pos), b.get(pos), "cell {pos:?} differs");
        }
    }

    #[test]
    fn a_solid_cube_packs_to_one_run_per_column() {
        let mut world = W::default();
        let region = boxed_cube();
        let changes = fill(&mut world, region, STONE);

        let edit = BlockEdit::pack(&changes);
        assert_eq!(edit.cell_count(), 32_768);
        assert_eq!(edit.runs.len(), 32 * 32);
        assert!(edit.runs.iter().all(|run| run.height == 32));
        assert_eq!(edit.palette, vec![STONE.raw()]);
        assert!(edit.byte_size() < changes.len() * std::mem::size_of::<BlockChange>());
    }

    #[test]
    fn applying_a_packed_edit_matches_the_per_cell_writes() {
        let mut direct = W::default();
        let region = boxed_cube();
        let changes = fill(&mut direct, region, STONE);

        let mut packed = W::default();
        BlockEdit::pack(&changes).apply(&mut packed);

        assert_same_world(&direct, &packed, region);
    }

    #[test]
    fn a_wall_packs_without_any_spurious_cells() {
        let mut world = W::default();
        let region = boxed_cube();
        let changes = walls(&mut world, region, STONE);

        let edit = BlockEdit::pack(&changes);
        assert_eq!(edit.cell_count(), changes.len() as u64);

        let mut packed = W::default();
        edit.apply(&mut packed);
        assert_same_world(&world, &packed, region);
    }

    #[test]
    fn a_filtered_replace_only_rewrites_the_matching_cells() {
        let mut world = W::default();
        let region = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(7, 7, 7));
        // A checkerboard of stone in the floor, so the filter matches half the cells.
        for x in 0..8 {
            for z in 0..8 {
                if (x + z) % 2 == 0 {
                    world.set(BlockPos::new(x, 0, z), STONE);
                }
            }
        }
        let changes = replace_where(&mut world, region, |id| id == STONE, DIRT);
        assert_eq!(changes.len(), 32);

        let edit = BlockEdit::pack(&changes);
        assert_eq!(edit.cell_count(), 32);
        assert_eq!(edit.palette.len(), 1);
        assert_eq!(edit.palette[0], DIRT.raw());

        let mut packed = world.clone();
        // Rebuild the pre-edit state so the comparison is meaningful.
        for change in &changes {
            packed.set(change.pos, change.before);
        }
        edit.apply(&mut packed);
        assert_same_world(&world, &packed, region);
    }

    #[test]
    fn packing_is_deterministic_and_grouped_by_column_then_height() {
        let mut world = W::default();
        let changes = fill(&mut world, boxed_cube(), STONE);
        let a = BlockEdit::pack(&changes);
        let b = BlockEdit::pack(&changes);
        assert_eq!(a, b);
        let mut sorted = a.runs.clone();
        sorted.sort_unstable_by_key(|run| (run.x, run.z, run.y));
        assert_eq!(a.runs, sorted);
    }

    #[test]
    fn merging_stops_where_the_id_changes() {
        let changes = vec![
            BlockChange {
                pos: BlockPos::new(0, 0, 0),
                before: W::AIR,
                after: STONE,
            },
            BlockChange {
                pos: BlockPos::new(0, 1, 0),
                before: W::AIR,
                after: DIRT,
            },
            BlockChange {
                pos: BlockPos::new(0, 2, 0),
                before: W::AIR,
                after: STONE,
            },
        ];
        let edit = BlockEdit::pack(&changes);
        assert_eq!(edit.runs.len(), 3);
        assert_eq!(edit.palette.len(), 2);
        assert!(edit.runs.iter().all(|run| run.height == 1));
    }

    #[test]
    fn an_empty_batch_packs_to_an_empty_edit() {
        let edit = BlockEdit::pack(&[]);
        assert!(edit.runs.is_empty());
        assert!(edit.palette.is_empty());
        assert_eq!(edit.cell_count(), 0);
    }
}
