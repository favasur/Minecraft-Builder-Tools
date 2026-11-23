//! The blocks the port writes, named, with ids that stay put.
//!
//! The core works with [`BlockId`], an opaque `u32`: region operations only compare, store and
//! restore it. Something has to say what a value *means* once the math is done, and that is this
//! catalog — the one place a number becomes `minecraft:stone`. The glue (`src/rig.rs`) resolves
//! these names to the host's block keys at registration and to block states when it writes.
//!
//! Ids are stable and written down here rather than assigned by a counter: a plan, an undo history
//! and a saved session all hold ids, and shuffling the list would silently turn a wall of stone
//! into a wall of dirt. `0` is air, as the core's [`World::AIR`](super::world::World::AIR) expects.

use super::world::BlockId;

/// Air: the id a `/set m/:air` writes and every region operation skips.
pub const AIR: BlockId = BlockId::new(0);

pub const STONE: BlockId = BlockId::new(1);
pub const DIRT: BlockId = BlockId::new(2);
pub const GRASS_BLOCK: BlockId = BlockId::new(3);
pub const COBBLESTONE: BlockId = BlockId::new(4);
pub const STONE_BRICKS: BlockId = BlockId::new(5);
pub const OAK_PLANKS: BlockId = BlockId::new(6);
pub const GRAVEL: BlockId = BlockId::new(7);
pub const SAND: BlockId = BlockId::new(8);
pub const GLASS: BlockId = BlockId::new(9);
pub const OAK_FENCE: BlockId = BlockId::new(10);
pub const OAK_LOG: BlockId = BlockId::new(11);
pub const SURVEYOR_STAKE: BlockId = BlockId::new(12);

/// Every block the port names, by id, in id order.
pub const CATALOG: &[(u32, &str)] = &[
    (1, "minecraft:stone"),
    (2, "minecraft:dirt"),
    (3, "minecraft:grass_block"),
    (4, "minecraft:cobblestone"),
    (5, "minecraft:stone_bricks"),
    (6, "minecraft:oak_planks"),
    (7, "minecraft:gravel"),
    (8, "minecraft:sand"),
    (9, "minecraft:glass"),
    (10, "minecraft:oak_fence"),
    (11, "minecraft:oak_log"),
    (12, "buildertools:surveyor_stake"),
];

/// The block's name, or `None` for air and for a value the catalog does not know.
pub fn name_of(block: BlockId) -> Option<&'static str> {
    CATALOG
        .iter()
        .find(|(raw, _)| *raw == block.raw())
        .map(|(_, name)| *name)
}

/// The id for a block name, or `None` when the catalog does not carry it.
///
/// The lookup is by exact namespaced name: `stone` is not `minecraft:stone`, because a bare name is
/// what a command types and the namespace is what the game stores.
pub fn id_of(name: &str) -> Option<BlockId> {
    CATALOG
        .iter()
        .find(|(_, known)| *known == name)
        .map(|(raw, _)| BlockId::new(*raw))
}

/// Whether the id is air.
pub const fn is_air(block: BlockId) -> bool {
    block.raw() == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn air_is_zero_and_not_in_the_catalog() {
        assert_eq!(AIR, BlockId::new(0));
        assert!(is_air(AIR));
        assert_eq!(name_of(AIR), None);
        assert_eq!(id_of("minecraft:air"), None);
        assert!(CATALOG.iter().all(|(raw, _)| *raw != 0));
    }

    #[test]
    fn every_id_and_every_name_is_unique() {
        for (index, (raw, name)) in CATALOG.iter().enumerate() {
            assert!(name.starts_with("minecraft:") || name.starts_with("buildertools:"), "{name}");
            assert!(
                CATALOG
                    .iter()
                    .filter(|(other, _)| other == raw)
                    .count()
                    == 1,
                "id {raw} appears twice"
            );
            assert!(
                CATALOG.iter().filter(|(_, other)| other == name).count() == 1,
                "{name} appears twice"
            );
            // Ids are dense from 1: a gap is a block someone forgot to keep a number for.
            assert_eq!(*raw as usize, index + 1, "ids are dense from 1");
        }
    }

    #[test]
    fn names_and_ids_round_trip() {
        for (raw, name) in CATALOG {
            let id = BlockId::new(*raw);
            assert_eq!(name_of(id), Some(*name));
            assert_eq!(id_of(name), Some(id));
        }
        assert_eq!(name_of(STONE), Some("minecraft:stone"));
        assert_eq!(id_of("buildertools:surveyor_stake"), Some(SURVEYOR_STAKE));
        assert_eq!(id_of("minecraft:not_a_block"), None);
        assert_eq!(name_of(BlockId::new(9_999)), None);
        assert!(!is_air(STONE));
    }

    #[test]
    fn the_settings_placeholders_keep_their_meaning() {
        // The settings and the session store have always called 1 stone and 2 dirt; the catalog
        // agrees, so a stored number does not change what it builds.
        assert_eq!(STONE, BlockId::new(1));
        assert_eq!(DIRT, BlockId::new(2));
    }
}
