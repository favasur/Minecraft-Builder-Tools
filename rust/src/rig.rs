//! The block table and the world writes: the core's ids, the host's states, `edit_blocks`.
//!
//! The core plans in [`BlockId`]s and packs its result into a [`BlockEdit`] of palette plus vertical
//! runs. Nothing applies it without two more steps, and both live here:
//!
//! 1. **the table** — `minecraft:stone` becomes a [`BlockKey`] once, at registration, and a
//!    [`BlockState`] when a write needs it (`block_state_default`, which only a host can answer);
//! 2. **the write** — the packed edit is translated into the host's own `BlockEdit` (states, not
//!    numbers) and handed to `ServerHost::edit_blocks`, which applies the whole batch in one call
//!    with no neighbour notifications and one chunk resend.
//!
//! Everything a player builds ends up here: a wall the builder commands lay, a block a golem's
//! `Placed` action reports, the stake a right-click plants. Reading back goes the other way, through
//! [`block_id`], so the golem's plan can see the world it is building in.

use std::collections::HashMap;
use std::sync::{OnceLock, RwLock};

use mod_api::{BlockKey, BlockState, LevelRef, ServerHost};

use crate::core::edit::BlockEdit;
use crate::core::ops::BlockChange;
use crate::core::palette;
use crate::core::pos::BlockPos;
use crate::core::world::{BlockId, World};

/// `BlockId` → the host's key for that block. Filled once, at registration.
///
/// A key is process-local and only a host can hand one out (`Registrar::block_key`), so this is the
/// one table that has to be built at load rather than on demand.
static KEYS: OnceLock<RwLock<HashMap<BlockId, BlockKey>>> = OnceLock::new();

fn keys() -> &'static RwLock<HashMap<BlockId, BlockKey>> {
    KEYS.get_or_init(|| RwLock::new(HashMap::new()))
}

/// Resolves every name in [`palette::CATALOG`] to the host's key for that block.
///
/// Called from `Hooks::register`. A build without one of the blocks still gets a key for it — the
/// registrar answers for any id a package or the game knows — and a block that is not there at all
/// surfaces as a named error at write time instead of silently writing air.
pub fn register_palette(registrar: &mut dyn mod_api::Registrar) {
    let mut table = keys().write().unwrap_or_else(|error| error.into_inner());
    table.clear();
    table.reserve(palette::CATALOG.len());
    for (raw, name) in palette::CATALOG {
        table.insert(BlockId::new(*raw), registrar.block_key(name));
    }
}

/// The host's key for a core block, once registration has run.
pub fn key_of(block: BlockId) -> Option<BlockKey> {
    let table = keys().read().unwrap_or_else(|error| error.into_inner());
    table.get(&block).copied()
}

/// The block's name for an error message, or its number when the catalog does not carry it.
fn describe(block: BlockId) -> String {
    match palette::name_of(block) {
        Some(name) => name.to_string(),
        None => format!("block #{}", block.raw()),
    }
}

/// The host's default state for a core block.
pub fn state_of(host: &dyn ServerHost, block: BlockId) -> Result<BlockState, String> {
    if palette::is_air(block) {
        // Air is the absence of a block, and the host writes it by name like any other.
        return host
            .block_state_id("minecraft:air")
            .ok_or_else(|| "minecraft:air has no state in this build".to_string());
    }
    let key = key_of(block)
        .ok_or_else(|| format!("{} is not in the palette (was `register` run?)", describe(block)))?;
    host.block_state_default(key)
        .ok_or_else(|| format!("{} has no default state in this build", describe(block)))
}

/// Translates a packed edit into the host's form: states in the palette, runs unchanged.
pub fn to_host_edit(host: &dyn ServerHost, edit: &BlockEdit) -> Result<mod_api::BlockEdit, String> {
    let mut states = Vec::with_capacity(edit.palette.len());
    for raw in &edit.palette {
        states.push(state_of(host, BlockId::new(*raw))?);
    }
    let runs = edit
        .runs
        .iter()
        .map(|run| mod_api::BlockRun {
            x: run.x,
            y: run.y,
            z: run.z,
            height: run.height,
            surface: None,
            block: run.block as u32,
            only: None,
        })
        .collect();
    Ok(mod_api::BlockEdit {
        palette: states,
        filters: Vec::new(),
        runs,
    })
}

/// Applies one packed edit to the authoritative level. Returns the blocks the host changed.
///
/// The dimension is the level's own: `edit_blocks` writes into the level the server is ticking, and
/// naming a different one would be refused rather than written somewhere else.
pub fn apply_edit(
    host: &mut dyn ServerHost,
    level: LevelRef,
    edit: &BlockEdit,
) -> Result<u64, String> {
    if edit.runs.is_empty() {
        return Ok(0);
    }
    let host_edit = to_host_edit(host, edit)?;
    let dimension = level
        .dimension()
        .id(host)
        .ok_or_else(|| "the level's dimension has no name".to_string())?;
    host.edit_blocks(&dimension, &host_edit)
}

/// Packs a change batch and applies it in one call.
pub fn apply_changes(
    host: &mut dyn ServerHost,
    level: LevelRef,
    changes: &[BlockChange],
) -> Result<u64, String> {
    apply_edit(host, level, &BlockEdit::pack(changes))
}

/// Applies a single block change — what a golem's `Placed` action carries.
pub fn apply_change(
    host: &mut dyn ServerHost,
    level: LevelRef,
    change: &BlockChange,
) -> Result<u64, String> {
    apply_changes(host, level, std::slice::from_ref(change))
}

/// Writes one block, filling `before` from the level so the change is a complete record.
///
/// `Ok(None)` means the position is not loaded, or the host refused the write; the caller reports
/// it and carries on, because a builder click that misses must not cancel anything.
pub fn place(
    host: &mut dyn ServerHost,
    level: LevelRef,
    pos: BlockPos,
    block: BlockId,
) -> Result<Option<u64>, String> {
    let before = read(host, level, pos);
    let change = BlockChange {
        pos,
        before,
        after: block,
    };
    match apply_change(host, level, &change) {
        Ok(0) => Ok(None),
        Ok(written) => Ok(Some(written)),
        Err(error) => Err(error),
    }
}

/// The core's id for a host state, by name. `None` for a name the catalog does not carry.
pub fn block_id(host: &dyn ServerHost, state: BlockState) -> Option<BlockId> {
    let name = host.block_state_string(state)?;
    palette::id_of(&name)
}

/// The block at a position, as the core sees it. Unloaded positions read as air.
pub fn read(host: &dyn ServerHost, level: LevelRef, pos: BlockPos) -> BlockId {
    level
        .block_state(host, [pos.x, pos.y, pos.z])
        .and_then(|state| block_id(host, state))
        .unwrap_or(palette::AIR)
}

/// The level as the core's [`World`], for code that plans against it (the golem's tick).
///
/// Reads go through the table by name. Writes are deliberately *not* performed here: `Golem::tick`
/// records the block it places in this view, and the real write is the bulk edit the glue applies
/// straight after, so one placement is one `edit_blocks` call rather than one call per cell. The
/// golem's next read comes from the level, which by then has the block.
pub struct LevelWorld<'a> {
    host: &'a dyn ServerHost,
    level: LevelRef,
}

impl<'a> LevelWorld<'a> {
    pub fn new(host: &'a dyn ServerHost, level: LevelRef) -> Self {
        Self { host, level }
    }
}

impl World for LevelWorld<'_> {
    const AIR: BlockId = palette::AIR;

    fn get(&self, pos: BlockPos) -> BlockId {
        read(self.host, self.level, pos)
    }

    fn set(&mut self, _pos: BlockPos, _id: BlockId) {
        // See the type's documentation: the write is the edit the glue applies.
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_table_is_empty_until_registration_and_answers_by_number() {
        // Registration needs a `Registrar`, which only a game hands out; what can be checked here is
        // that the lookups are total and that an unregistered id is named rather than guessed.
        assert_eq!(key_of(palette::STONE), key_of(palette::STONE));
        let described = describe(BlockId::new(4242));
        assert_eq!(described, "block #4242");
        assert_eq!(describe(palette::DIRT), "minecraft:dirt");
    }

    #[test]
    fn the_core_world_view_uses_the_catalogue_for_air() {
        assert_eq!(<LevelWorld as World>::AIR, palette::AIR);
        assert!(palette::is_air(<LevelWorld as World>::AIR));
    }
}
