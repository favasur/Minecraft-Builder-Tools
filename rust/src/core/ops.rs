//! The WorldEdit-style region operations, ported one-for-one from
//! `server/BuilderCommand.java` and `server/BuilderServerHandler.java`.

use super::pos::{BlockBox, BlockPos};
use super::world::{BlockId, World};

/// Maximum number of blocks a single operation may touch (`BuilderServerHandler.MAX_BLOCKS`).
pub const MAX_BLOCKS: u64 = 32_768;
/// Operations farther than this from the player are rejected (`MAX_DISTANCE`).
pub const MAX_DISTANCE: f64 = 256.0;

/// One changed block. The Java record keeps only the state before the change; the port records
/// both sides so `/redo` can put the new state back instead of re-applying the old one.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct BlockChange {
    pub pos: BlockPos,
    pub before: BlockId,
    pub after: BlockId,
}

/// Writes `id` and records the change. Like the Java `applyRegion`, a cell counts even when it
/// already holds `id`, so the reported total and the undo batch match the Java mod's.
fn place<W: World>(world: &mut W, out: &mut Vec<BlockChange>, pos: BlockPos, id: BlockId) {
    let before = world.get(pos);
    world.set(pos, id);
    out.push(BlockChange {
        pos,
        before,
        after: id,
    });
}

/// Writes `id` only when the cell differs, as the Java `/hollow` loop does.
fn place_if_changed<W: World>(
    world: &mut W,
    out: &mut Vec<BlockChange>,
    pos: BlockPos,
    id: BlockId,
) {
    if world.get(pos) == id {
        return;
    }
    place(world, out, pos, id);
}

/// `/set <block>`: every cell of the region.
pub fn fill<W: World>(world: &mut W, region: BlockBox, id: BlockId) -> Vec<BlockChange> {
    let mut out = Vec::with_capacity(region.volume().min(MAX_BLOCKS) as usize);
    for pos in region.iter() {
        place(world, &mut out, pos, id);
    }
    out
}

/// `/clear`: every non-air cell becomes air.
pub fn clear<W: World>(world: &mut W, region: BlockBox) -> Vec<BlockChange> {
    replace_where(world, region, |current| current != W::AIR, W::AIR)
}

/// `/replace <to>`: every non-air cell.
pub fn replace_all<W: World>(world: &mut W, region: BlockBox, id: BlockId) -> Vec<BlockChange> {
    replace_where(world, region, |current| current != W::AIR, id)
}

/// `/replace <from> <to>`: every cell satisfying `matches`.
pub fn replace_where<W: World>(
    world: &mut W,
    region: BlockBox,
    matches: impl Fn(BlockId) -> bool,
    id: BlockId,
) -> Vec<BlockChange> {
    let mut out = Vec::new();
    for pos in region.iter() {
        if !matches(world.get(pos)) {
            continue;
        }
        place(world, &mut out, pos, id);
    }
    out
}

/// `/walls <block>`: the four vertical sides.
pub fn walls<W: World>(world: &mut W, region: BlockBox, id: BlockId) -> Vec<BlockChange> {
    let mut out = Vec::new();
    for pos in region.iter() {
        if region.is_wall(pos) {
            place(world, &mut out, pos, id);
        }
    }
    out
}

/// `/outline <block>`: the Java mod's outline is the whole shell (any axis on a boundary), not the
/// 12 edges its README describes. The port keeps the shipped behaviour.
pub fn outline<W: World>(world: &mut W, region: BlockBox, id: BlockId) -> Vec<BlockChange> {
    let mut out = Vec::new();
    for pos in region.iter() {
        if region.is_boundary(pos) {
            place(world, &mut out, pos, id);
        }
    }
    out
}

/// `/hollow <block>`: the shell becomes `id`, the inside becomes air. Unchanged cells are skipped,
/// as in the Java loop.
pub fn hollow<W: World>(world: &mut W, region: BlockBox, id: BlockId) -> Vec<BlockChange> {
    let mut out = Vec::new();
    for pos in region.iter() {
        let target = if region.is_boundary(pos) { id } else { W::AIR };
        place_if_changed(world, &mut out, pos, target);
    }
    out
}

/// `/faces <block>`: the top and bottom faces only.
pub fn faces<W: World>(world: &mut W, region: BlockBox, id: BlockId) -> Vec<BlockChange> {
    let mut out = Vec::new();
    for pos in region.iter() {
        if region.is_horizontal_face(pos) {
            place(world, &mut out, pos, id);
        }
    }
    out
}

/// `/overlay <block>`: one block above the topmost non-air block of every column.
pub fn overlay<W: World>(world: &mut W, region: BlockBox, id: BlockId) -> Vec<BlockChange> {
    let mut out = Vec::new();
    let (min, max) = (region.min(), region.max());
    for x in min.x..=max.x {
        for z in min.z..=max.z {
            let mut top: Option<i32> = None;
            let mut y = max.y;
            while y >= min.y {
                if world.get(BlockPos::new(x, y, z)) != W::AIR {
                    top = Some(y);
                    break;
                }
                y -= 1;
            }
            if let Some(top) = top
                && top >= min.y
                && top < max.y
            {
                place(world, &mut out, BlockPos::new(x, top + 1, z), id);
            }
        }
    }
    out
}

/// `/center <block>`: the 1-2 block centre of the region.
pub fn center<W: World>(world: &mut W, region: BlockBox, id: BlockId) -> Vec<BlockChange> {
    let mut out = Vec::new();
    for pos in region.center_box().iter() {
        place(world, &mut out, pos, id);
    }
    out
}

/// `/stack <count> [dir]`: repeats the region's non-air contents along `dir`, `span` blocks apart.
pub fn stack<W: World>(
    world: &mut W,
    region: BlockBox,
    dir: BlockPos,
    count: i32,
) -> Vec<BlockChange> {
    let axis = if dir.x != 0 {
        0
    } else if dir.y != 0 {
        1
    } else {
        2
    };
    let span = region.size()[axis];
    let mut out = Vec::new();
    for i in 1..=count {
        let offset = span * i;
        for pos in region.iter() {
            let state = world.get(pos);
            if state == W::AIR {
                continue;
            }
            let target = pos.offset(dir.x * offset, dir.y * offset, dir.z * offset);
            place(world, &mut out, target, state);
        }
    }
    out
}

/// `/move <count> [dir]`: clears the region and re-places its non-air contents `count` blocks away.
/// The Java version keeps the entity data alongside; the port carries the block id and leaves block
/// entity data to the Horizon glue.
pub fn move_region<W: World>(
    world: &mut W,
    region: BlockBox,
    dir: BlockPos,
    count: i32,
) -> Vec<BlockChange> {
    let min = region.min();
    let mut entries: Vec<(BlockPos, BlockId)> = Vec::new();
    for pos in region.iter() {
        let state = world.get(pos);
        if state != W::AIR {
            entries.push((BlockPos::new(pos.x - min.x, pos.y - min.y, pos.z - min.z), state));
        }
    }

    let mut out = Vec::new();
    for pos in region.iter() {
        place(world, &mut out, pos, W::AIR);
    }
    for (rel, state) in entries {
        let target = min.offset(rel.x + dir.x * count, rel.y + dir.y * count, rel.z + dir.z * count);
        place(world, &mut out, target, state);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[derive(Default)]
    struct TestWorld {
        cells: HashMap<BlockPos, BlockId>,
    }

    impl World for TestWorld {
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

    fn box3() -> BlockBox {
        BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(2, 2, 2))
    }

    #[test]
    fn fill_touches_every_cell() {
        let mut w = TestWorld::default();
        let changes = fill(&mut w, box3(), STONE);
        assert_eq!(changes.len(), 27);
        assert!(box3().iter().all(|p| w.get(p) == STONE));
    }

    #[test]
    fn replace_all_skips_air_and_replace_where_uses_predicate() {
        let mut w = TestWorld::default();
        w.set(BlockPos::new(0, 0, 0), STONE);
        w.set(BlockPos::new(1, 0, 0), DIRT);

        let changes = replace_all(&mut w, box3(), DIRT);
        assert_eq!(changes.len(), 2);
        assert_eq!(w.get(BlockPos::new(0, 0, 0)), DIRT);

        let changes = replace_where(&mut w, box3(), |id| id == DIRT, STONE);
        assert_eq!(changes.len(), 2);
        assert_eq!(w.get(BlockPos::new(0, 0, 0)), STONE);
    }

    #[test]
    fn walls_outline_faces_and_hollow_hit_the_expected_cells() {
        let b = box3();
        let walls = b.iter().filter(|p| b.is_wall(*p)).count();
        assert_eq!(walls, 24); // the 8 edge cells of each of the 3 layers
        let faces = b.iter().filter(|p| b.is_horizontal_face(*p)).count();
        assert_eq!(faces, 18);
        let shell = b.iter().filter(|p| b.is_boundary(*p)).count();
        assert_eq!(shell, 26);

        let mut w = TestWorld::default();
        let changes = hollow(&mut w, b, STONE);
        assert_eq!(changes.len(), 26);
        assert_eq!(w.get(BlockPos::new(1, 1, 1)), TestWorld::AIR);
        assert_eq!(w.get(BlockPos::new(0, 1, 1)), STONE);
    }

    #[test]
    fn overlay_places_one_block_above_each_column_top() {
        let mut w = TestWorld::default();
        w.set(BlockPos::new(1, 1, 1), STONE);
        let changes = overlay(&mut w, box3(), DIRT);
        assert_eq!(changes.len(), 1);
        assert_eq!(w.get(BlockPos::new(1, 2, 1)), DIRT);
    }

    #[test]
    fn overlay_skips_a_column_whose_top_is_the_ceiling() {
        let mut w = TestWorld::default();
        w.set(BlockPos::new(1, 2, 1), STONE);
        let changes = overlay(&mut w, box3(), DIRT);
        assert!(changes.is_empty());
    }

    #[test]
    fn center_sets_the_middle_block_of_an_even_region() {
        let mut w = TestWorld::default();
        let b = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(2, 2, 2));
        let changes = center(&mut w, b, STONE);
        assert_eq!(changes.len(), 1);
        assert_eq!(w.get(BlockPos::new(1, 1, 1)), STONE);
    }

    #[test]
    fn stack_repeats_the_region_and_move_moves_it() {
        let mut w = TestWorld::default();
        let b = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(1, 0, 0));
        fill(&mut w, b, STONE);

        let changes = stack(&mut w, b, BlockPos::new(1, 0, 0), 2);
        assert_eq!(changes.len(), 4);
        assert_eq!(w.get(BlockPos::new(2, 0, 0)), STONE);
        assert_eq!(w.get(BlockPos::new(3, 0, 0)), STONE);

        let mut m = TestWorld::default();
        fill(&mut m, b, DIRT);
        let changes = move_region(&mut m, b, BlockPos::new(0, 1, 0), 3);
        assert_eq!(m.get(BlockPos::new(0, 0, 0)), TestWorld::AIR);
        assert_eq!(m.get(BlockPos::new(0, 3, 0)), DIRT);
        assert!(changes.len() >= 4);
    }

    #[test]
    fn volume_and_iteration_agree_for_a_tall_box() {
        let b = BlockBox::new(BlockPos::new(-3, 0, 5), BlockPos::new(2, 40, 9));
        assert_eq!(b.volume(), 6 * 41 * 5);
        assert_eq!(b.iter().count(), b.volume() as usize);
    }
}
