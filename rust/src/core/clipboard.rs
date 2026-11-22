//! Per-player clipboard, ported from `server/ClipboardStore.java` and `copySelection`/`paste`.

use super::ops::BlockChange;
use super::pos::{BlockBox, BlockPos};
use super::world::{BlockId, World};

/// A copied region, stored relative to its own minimum corner.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Clipboard {
    size: [i32; 3],
    /// Row-major by y, then z, then x. `None` marks air.
    cells: Vec<Option<BlockId>>,
}

impl Clipboard {
    /// `/copy`: snapshots every non-air cell of the region.
    pub fn copy<W: World>(world: &W, region: BlockBox) -> Self {
        let size = region.size();
        let mut cells = Vec::with_capacity(region.volume() as usize);
        for pos in region.iter() {
            let id = world.get(pos);
            cells.push(if id == W::AIR { None } else { Some(id) });
        }
        Self { size, cells }
    }

    pub const fn size(&self) -> [i32; 3] {
        self.size
    }

    pub fn volume(&self) -> u64 {
        self.size[0] as u64 * self.size[1] as u64 * self.size[2] as u64
    }

    /// The number of non-air cells the clipboard holds.
    pub fn block_count(&self) -> usize {
        self.cells.iter().filter(|cell| cell.is_some()).count()
    }

    pub fn is_empty(&self) -> bool {
        self.block_count() == 0
    }

    fn index(&self, rel: BlockPos) -> Option<usize> {
        if rel.x < 0 || rel.y < 0 || rel.z < 0 {
            return None;
        }
        let (sx, sy, sz) = (self.size[0], self.size[1], self.size[2]);
        if rel.x >= sx || rel.y >= sy || rel.z >= sz {
            return None;
        }
        Some(((rel.y * sz + rel.z) * sx + rel.x) as usize)
    }

    /// The block at a cell relative to the clipboard's minimum corner.
    pub fn get(&self, rel: BlockPos) -> Option<BlockId> {
        self.index(rel).and_then(|i| self.cells[i])
    }

    /// `/paste`: places the clipboard so its minimum corner is at `anchor`, skipping air.
    pub fn paste<W: World>(&self, world: &mut W, anchor: BlockPos) -> Vec<BlockChange> {
        let mut out = Vec::new();
        for y in 0..self.size[1] {
            for z in 0..self.size[2] {
                for x in 0..self.size[0] {
                    let rel = BlockPos::new(x, y, z);
                    let Some(id) = self.get(rel) else {
                        continue;
                    };
                    let target = anchor.offset(x, y, z);
                    let before = world.get(target);
                    if before == id {
                        continue;
                    }
                    world.set(target, id);
                    out.push(BlockChange {
                        pos: target,
                        before,
                        after: id,
                    });
                }
            }
        }
        out
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
    const DIRT: BlockId = BlockId::new(2);

    #[test]
    fn copy_skips_air_and_paste_restores_offsets() {
        let mut world = W::default();
        let region = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(1, 0, 1));
        world.set(BlockPos::new(0, 0, 0), STONE);
        world.set(BlockPos::new(1, 0, 1), DIRT);

        let clip = Clipboard::copy(&world, region);
        assert_eq!(clip.size(), [2, 1, 2]);
        assert_eq!(clip.block_count(), 2);
        assert_eq!(clip.get(BlockPos::new(0, 0, 0)), Some(STONE));
        assert_eq!(clip.get(BlockPos::new(0, 0, 1)), None);

        let changes = clip.paste(&mut world, BlockPos::new(10, 5, 10));
        assert_eq!(changes.len(), 2);
        assert_eq!(world.get(BlockPos::new(10, 5, 10)), STONE);
        assert_eq!(world.get(BlockPos::new(11, 5, 11)), DIRT);
        assert_eq!(world.get(BlockPos::new(10, 5, 11)), W::AIR);
    }

    #[test]
    fn empty_clipboard_reports_empty_and_pastes_nothing() {
        let world = W::default();
        let region = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(2, 2, 2));
        let clip = Clipboard::copy(&world, region);
        assert!(clip.is_empty());
        let mut world = world;
        assert!(clip.paste(&mut world, BlockPos::new(0, 0, 0)).is_empty());
    }
}
