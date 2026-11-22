//! The brush tools: paint, scatter and smooth.
//!
//! Ported from `BuilderServerHandler.paint`, `scatter` and `smooth` (radius 4, sphere-shaped). Each
//! brush turns a click into an undoable change batch:
//!
//! * **Paint** writes the held block into every cell of the sphere.
//! * **Scatter** writes it into half of the sphere's *surface* cells: air that sits on something
//!   solid, chosen by the caller's random source so the result is reproducible in tests.
//! * **Smooth** levels the terrain under the disc: it finds each column's top within the sphere,
//!   takes the mean height, shaves the high columns and raises the low ones with their own surface
//!   block.
//!
//! The Java brush collected a `List<BlockPos>` per click; here the sphere is walked in place into a
//! reusable buffer, so a click allocates the change batch and nothing else.

use super::ops::{BlockChange, MAX_BLOCKS};
use super::pos::BlockPos;
use super::world::{BlockId, World};

/// The brush radius the Java tools use, in blocks.
pub const BRUSH_RADIUS: i32 = 4;

/// How far above the click the smooth brush looks for a column's top.
const SMOOTH_UP: i32 = 3;
/// How far below it.
const SMOOTH_DOWN: i32 = 6;

/// Every cell of a sphere of `radius` around `centre`, appended to `out`.
///
/// A cell is in the sphere when its *centre* is: the same test the Java brush makes with
/// `+0.5` offsets, so an odd radius is symmetric and a click paints the same shape everywhere.
pub fn sphere_into(centre: BlockPos, radius: i32, out: &mut Vec<BlockPos>) {
    out.clear();
    let radius = radius.max(0);
    let r2 = (radius * radius) as f64;
    for dy in -radius..=radius {
        for dz in -radius..=radius {
            for dx in -radius..=radius {
                let distance = (dx * dx + dy * dy + dz * dz) as f64;
                if distance > r2 {
                    continue;
                }
                out.push(BlockPos::new(
                    centre.x + dx,
                    centre.y + dy,
                    centre.z + dz,
                ));
            }
        }
    }
}

/// The cells of a sphere, as a fresh vector. Convenience for the glue and for tests.
pub fn sphere(centre: BlockPos, radius: i32) -> Vec<BlockPos> {
    let mut out = Vec::with_capacity((radius.max(0) as usize * 2 + 1).pow(3));
    sphere_into(centre, radius, &mut out);
    out
}

/// Paints `block` into the sphere around `centre`. Cells that already hold it are left alone, so a
/// second click in the same place records nothing.
pub fn paint<W: World>(world: &mut W, centre: BlockPos, block: BlockId) -> Vec<BlockChange> {
    let mut positions = Vec::new();
    sphere_into(centre, BRUSH_RADIUS, &mut positions);
    let mut changes = Vec::with_capacity(positions.len());
    for pos in positions {
        if changes.len() as u64 >= MAX_BLOCKS {
            break;
        }
        let before = world.get(pos);
        if before == block {
            continue;
        }
        world.set(pos, block);
        changes.push(BlockChange {
            pos,
            before,
            after: block,
        });
    }
    changes
}

/// Scatters `block` over the sphere's surface: air cells whose cell below is solid, taken when
/// `random()` is at most `chance`. The Java brush used a flat `0.5`; the chance is a parameter so a
/// dense and a sparse scatter are the same code.
pub fn scatter<W: World, R: FnMut() -> f64>(
    world: &mut W,
    centre: BlockPos,
    block: BlockId,
    chance: f64,
    mut random: R,
) -> Vec<BlockChange> {
    let chance = chance.clamp(0.0, 1.0);
    let mut positions = Vec::new();
    sphere_into(centre, BRUSH_RADIUS, &mut positions);
    let mut changes = Vec::with_capacity(positions.len() / 2);
    for pos in positions {
        if changes.len() as u64 >= MAX_BLOCKS {
            break;
        }
        if random() > chance {
            continue;
        }
        if world.get(pos) != W::AIR {
            continue;
        }
        let below = BlockPos::new(pos.x, pos.y - 1, pos.z);
        if world.get(below) == W::AIR {
            continue;
        }
        world.set(pos, block);
        changes.push(BlockChange {
            pos,
            before: W::AIR,
            after: block,
        });
    }
    changes
}

/// One column the smooth brush found.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Column {
    pub x: i32,
    pub z: i32,
    /// The y of the column's top block.
    pub y: i32,
    /// What that block is, used to raise the column.
    pub surface: BlockId,
}

/// The columns under the disc around `centre`, as the smooth brush sees them.
pub fn columns<W: World>(world: &W, centre: BlockPos) -> Vec<Column> {
    let mut out = Vec::new();
    for dz in -BRUSH_RADIUS..=BRUSH_RADIUS {
        for dx in -BRUSH_RADIUS..=BRUSH_RADIUS {
            if dx * dx + dz * dz > BRUSH_RADIUS * BRUSH_RADIUS {
                continue;
            }
            let x = centre.x + dx;
            let z = centre.z + dz;
            let mut y = centre.y + SMOOTH_UP;
            while y > centre.y - SMOOTH_DOWN && world.get(BlockPos::new(x, y, z)) == W::AIR {
                y -= 1;
            }
            let surface = world.get(BlockPos::new(x, y, z));
            if surface == W::AIR {
                continue;
            }
            out.push(Column { x, z, y, surface });
        }
    }
    out
}

/// Levels the terrain under the disc to its mean height: high columns are shaved, low ones raised
/// with their own surface block. Returns the change batch; empty when the disc is already level.
pub fn smooth<W: World>(world: &mut W, centre: BlockPos) -> Vec<BlockChange> {
    let found = columns(world, centre);
    if found.is_empty() {
        return Vec::new();
    }
    let total: i64 = found.iter().map(|column| column.y as i64).sum();
    let target = (total as f64 / found.len() as f64).round() as i32;

    let mut changes = Vec::new();
    for column in found {
        if column.y == target {
            continue;
        }
        if column.y > target {
            for y in (target + 1)..=column.y {
                if changes.len() as u64 >= MAX_BLOCKS {
                    return changes;
                }
                let pos = BlockPos::new(column.x, y, column.z);
                let before = world.get(pos);
                if before == W::AIR {
                    continue;
                }
                world.set(pos, W::AIR);
                changes.push(BlockChange {
                    pos,
                    before,
                    after: W::AIR,
                });
            }
        } else {
            for y in (column.y + 1)..=target {
                if changes.len() as u64 >= MAX_BLOCKS {
                    return changes;
                }
                let pos = BlockPos::new(column.x, y, column.z);
                let before = world.get(pos);
                world.set(pos, column.surface);
                changes.push(BlockChange {
                    pos,
                    before,
                    after: column.surface,
                });
            }
        }
    }
    changes
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
    const GRASS: BlockId = BlockId::new(2);
    const GLASS: BlockId = BlockId::new(3);

    fn fill_ground(world: &mut W, top: i32) {
        for x in -8..=8 {
            for z in -8..=8 {
                for y in -2..=top {
                    world.set(BlockPos::new(x, y, z), STONE);
                }
            }
        }
    }

    #[test]
    fn a_sphere_is_symmetric_and_stays_inside_its_radius() {
        let one = sphere(BlockPos::new(0, 0, 0), 1);
        assert_eq!(one.len(), 7, "the centre and its six neighbours");

        let four = sphere(BlockPos::new(0, 0, 0), BRUSH_RADIUS);
        for pos in &four {
            let distance = (pos.x * pos.x + pos.y * pos.y + pos.z * pos.z) as f64;
            assert!(distance <= (BRUSH_RADIUS * BRUSH_RADIUS) as f64);
        }
        // No cell in the corner of the bounding box: a sphere, not a cube.
        assert!(!four.contains(&BlockPos::new(4, 4, 4)));
        // Mirrored cells are all present.
        for pos in &four {
            assert!(four.contains(&BlockPos::new(-pos.x, -pos.y, -pos.z)));
        }
    }

    #[test]
    fn painting_fills_the_sphere_and_records_every_change() {
        let mut world = W::default();
        let changes = paint(&mut world, BlockPos::new(0, 0, 0), GLASS);
        assert!(!changes.is_empty());
        assert_eq!(changes.len(), sphere(BlockPos::new(0, 0, 0), BRUSH_RADIUS).len());
        assert!(changes.iter().all(|change| change.before == W::AIR && change.after == GLASS));
        assert_eq!(world.get(BlockPos::new(0, 0, 0)), GLASS);
    }

    #[test]
    fn painting_the_same_block_twice_does_nothing_the_second_time() {
        let mut world = W::default();
        paint(&mut world, BlockPos::new(0, 0, 0), GLASS);
        assert!(paint(&mut world, BlockPos::new(0, 0, 0), GLASS).is_empty());
    }

    #[test]
    fn scatter_only_touches_air_that_sits_on_something_solid() {
        let mut world = W::default();
        fill_ground(&mut world, 0);
        let changes = scatter(&mut world, BlockPos::new(0, 0, 0), GLASS, 1.0, || 0.0);
        assert!(!changes.is_empty());
        for change in &changes {
            // Every scattered cell was air over something solid. The cell below may itself have
            // been scattered into, since the sphere reaches down to the ground.
            assert_eq!(change.before, W::AIR);
            let below = world.get(BlockPos::new(change.pos.x, change.pos.y - 1, change.pos.z));
            assert_ne!(below, W::AIR, "a scattered cell floats over air");
            assert_eq!(change.after, GLASS);
        }
        // The cell below the ground surface is never touched.
        assert_eq!(world.get(BlockPos::new(0, 0, 0)), STONE);
    }

    #[test]
    fn the_scatter_chance_bounds_what_is_placed() {
        let mut world = W::default();
        fill_ground(&mut world, 0);
        // A random source that always says "no".
        let none = scatter(&mut world, BlockPos::new(0, 0, 0), GLASS, 0.5, || 1.0);
        assert!(none.is_empty());
        // Always "yes" places something over the same ground.
        let all = scatter(&mut world, BlockPos::new(0, 0, 0), GLASS, 0.5, || 0.0);
        assert!(!all.is_empty());
    }

    #[test]
    fn the_smooth_brush_flattens_a_bump_to_the_disc_mean() {
        let mut world = W::default();
        for x in -8..=8 {
            for z in -8..=8 {
                for y in -2..=1 {
                    world.set(BlockPos::new(x, y, z), GRASS);
                }
            }
        }
        // A three-high bump in the middle of the disc.
        for y in 2..=4 {
            world.set(BlockPos::new(0, y, 0), GRASS);
        }

        let target = BlockPos::new(0, 1, 0);
        let changes = smooth(&mut world, target);
        assert!(!changes.is_empty());
        assert!(changes.iter().all(|change| change.after == W::AIR), "a bump is shaved");
        assert_eq!(world.get(BlockPos::new(0, 4, 0)), W::AIR);
        assert_eq!(world.get(BlockPos::new(0, 1, 0)), GRASS, "the floor survives");
    }

    #[test]
    fn the_smooth_brush_raises_a_pit_with_the_pit_s_own_block() {
        let mut world = W::default();
        for x in -8..=8 {
            for z in -8..=8 {
                for y in -2..=1 {
                    world.set(BlockPos::new(x, y, z), STONE);
                }
            }
        }
        // A pit: the centre column's surface is a grass block two lower.
        for y in 0..=1 {
            world.cells.remove(&BlockPos::new(0, y, 0));
        }
        world.set(BlockPos::new(0, -1, 0), GRASS);

        let changes = smooth(&mut world, BlockPos::new(0, 1, 0));
        assert!(!changes.is_empty());
        assert!(changes.iter().all(|change| change.after == GRASS));
        assert_eq!(world.get(BlockPos::new(0, 1, 0)), GRASS, "the pit is filled to the mean");
    }

    #[test]
    fn a_level_disc_needs_no_work() {
        let mut world = W::default();
        fill_ground(&mut world, 0);
        assert!(smooth(&mut world, BlockPos::new(0, 0, 0)).is_empty());
    }

    #[test]
    fn the_columns_are_a_disc_not_a_square() {
        let mut world = W::default();
        fill_ground(&mut world, 0);
        let found = columns(&world, BlockPos::new(0, 0, 0));
        assert!(found.iter().all(|column| column.x * column.x + column.z * column.z <= BRUSH_RADIUS * BRUSH_RADIUS));
        assert!(!found.iter().any(|column| column.x == 4 && column.z == 4));
        let corners = found
            .iter()
            .filter(|column| column.x.abs() == 4 && column.z.abs() == 4)
            .count();
        assert_eq!(corners, 0, "the corners of the square are outside the disc");
    }
}
