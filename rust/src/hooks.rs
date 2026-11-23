//! The game-facing half: hooks in, core calls out.
//!
//! Everything the player can see or touch goes through here. The core does the arithmetic; this
//! module only answers "which player", "which item", "which rule", and hands geometry back to the
//! renderer. Keeping it that thin is what lets the whole of `core` be tested without a game.
//!
//! What is wired today:
//!
//! * a right-click with the surveyor stake plants a housing stake in that player's session,
//! * the local player's housing preview is submitted as world geometry every frame,
//! * `GolemFetching` is read from the level once per tick and cached for the golem code,
//! * enlisted golems are ticked on the server and their placements are written to the world.
//!
//! What is not, and why, is in the README: applying a golem's actions needs a block palette that
//! maps the core's opaque `BlockId`s onto the host's block states, and that table belongs to the
//! tool layer that selects a block in the first place.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use mod_api::{
    ClientHost, Flow, GameRuleChangedEvent, GameRuleValue, HostSide, Identifier, InteractionResult,
    LevelRef, LevelTickPostEvent, LogicalSide, RenderLevelStageEvent, ServerHost,
    UseItemOnBlockEvent, UsePhase, WorldDepth, WorldLine, WorldQuad,
};

use crate::core::golem::{Golem, GolemAction, GolemRules};
use crate::core::ops::BlockChange;
use crate::core::palette;
use crate::core::pos::BlockPos;
use crate::core::preview::PreviewDepth;
use crate::core::sessions::{PlayerId, Sessions};
use crate::core::world::BlockId;


/// The item that plants a surveyor stake, as the content declares it.
pub const STAKE_ITEM: &str = "buildertools:surveyor_stake";

/// The registered rule's namespaced id, as a change event spells it.
pub const RULE_ID: &str = "buildertools:golem_fetching";

/// The sessions of this side. In a single-player world the integrated server and the client share
/// the process's statics, so the server side is the only writer: a click plants once.
static SESSIONS: Mutex<Option<Sessions>> = Mutex::new(None);

/// The last value read back from the level's `GolemFetching` rule.
static FETCHING: AtomicBool = AtomicBool::new(true);

/// Blocks a hook asked for without a level handle in hand, written on the next server tick.
///
/// A right-click knows the position and the block but not the level it happened in; the level tick
/// does. Queueing them also means a burst of clicks becomes one `edit_blocks` call.
static PENDING: Mutex<Vec<(BlockPos, BlockId)>> = Mutex::new(Vec::new());

/// Queues one block to write on the next server tick.
pub fn queue_write(pos: BlockPos, block: BlockId) {
    let mut guard = PENDING.lock().unwrap_or_else(|error| error.into_inner());
    guard.push((pos, block));
}

/// How many writes are waiting for the next server tick.
pub fn pending_count() -> usize {
    let guard = PENDING.lock().unwrap_or_else(|error| error.into_inner());
    guard.len()
}

/// Writes everything queued, as one packed edit. Returns how many blocks the host changed.
fn flush_pending(host: &mut dyn ServerHost, level: LevelRef) -> Result<u64, String> {
    let queued = {
        let mut guard = PENDING.lock().unwrap_or_else(|error| error.into_inner());
        std::mem::take(&mut *guard)
    };
    if queued.is_empty() {
        return Ok(0);
    }
    let changes: Vec<BlockChange> = queued
        .into_iter()
        .map(|(pos, after)| BlockChange {
            pos,
            before: crate::rig::read(host, level, pos),
            after,
        })
        .collect();
    crate::rig::apply_changes(host, level, &changes)
}

/// The golems this side is building with, in enlistment order.
///
/// A golem is a core value: its plan, its hands and its cadence. The entity that carries it in the
/// world is the game's; enlisting it here is what makes its `Placed` actions reach the level.
static GOLEMS: Mutex<Vec<Golem>> = Mutex::new(Vec::new());

/// The wall block a session starts with. Real builds take their block from the tool in hand; until
/// that layer exists, stone stands in so a session is always valid *and* builds something real.
const PLACEHOLDER_WALL: BlockId = palette::STONE;

/// The stable key a player's claims and golem origins are written with.
///
/// The game's name is not stable across a rename and is not available on both sides of every hook,
/// so the UUID is the identity here. A claim reads `player:0000000000000000000000000000abcd`.
pub fn owner_key(player: PlayerId) -> String {
    format!("player:{player:032x}")
}

/// Runs `f` against this side's sessions, creating the store on first use.
fn with_sessions<R>(f: impl FnOnce(&mut Sessions) -> R) -> R {
    let mut guard = SESSIONS.lock().unwrap_or_else(|error| error.into_inner());
    let sessions = guard.get_or_insert_with(|| Sessions::new(PLACEHOLDER_WALL));
    f(sessions)
}

/// Plants a housing stake for `player` at `pos`. `false` when their survey is full.
pub fn plant_stake(player: PlayerId, pos: [i32; 3]) -> bool {
    let owner = owner_key(player);
    with_sessions(|sessions| {
        sessions
            .plant_housing(player, owner, crate::BlockPos::new(pos[0], pos[1], pos[2]))
            .is_some()
    })
}

/// How many housing stakes `player` has down, for the diagnostics in the README's checks.
pub fn stakes_down(player: PlayerId) -> usize {
    with_sessions(|sessions| {
        sessions
            .session(player)
            .map(|session| session.housing.len())
            .unwrap_or(0)
    })
}

/// Whether the level's rule currently lets golems fetch.
pub fn fetching_enabled() -> bool {
    FETCHING.load(Ordering::Relaxed)
}

/// Adds a golem to the ones this side ticks and lets it build.
pub fn enlist(golem: Golem) {
    let mut guard = GOLEMS.lock().unwrap_or_else(|error| error.into_inner());
    guard.push(golem);
}

/// How many golems are enlisted, for the diagnostics and the tests.
pub fn golem_count() -> usize {
    let guard = GOLEMS.lock().unwrap_or_else(|error| error.into_inner());
    guard.len()
}

/// Forgets every enlisted golem: for tests, and for leaving a world.
pub fn forget_golems() {
    let mut guard = GOLEMS.lock().unwrap_or_else(|error| error.into_inner());
    guard.clear();
}

/// One tick of golem work on the authoritative level.
///
/// Each golem is ticked against the level it is building in — its plan is read through
/// [`crate::rig::LevelWorld`], so "is the block already there?" is answered by the world itself — and
/// an action that places a block is written through the same bulk edit every other build uses. The
/// other actions need the entity code (a fetch needs a block entity to walk to), so they are left to
/// it; nothing here invents a block a golem does not hold.
pub fn tick_golems(host: &mut dyn ServerHost, level: LevelRef) {
    let _ = flush_pending(host, level);
    let mut golems = GOLEMS.lock().unwrap_or_else(|error| error.into_inner());
    if golems.is_empty() {
        return;
    }
    let sessions = SESSIONS.lock().unwrap_or_else(|error| error.into_inner());
    let fallback = Sessions::new(PLACEHOLDER_WALL);
    let claims = match sessions.as_ref() {
        Some(sessions) => sessions.claims(),
        None => fallback.claims(),
    };
    let rules = GolemRules {
        fetching: fetching_enabled(),
    };

    for golem in golems.iter_mut() {
        let action = {
            let mut world = crate::rig::LevelWorld::new(host, level);
            golem.tick(&mut world, claims, rules)
        };
        if let GolemAction::Placed { change, .. } = action {
            let _ = crate::rig::apply_change(host, level, &change);
        }
    }
}

/// The preview geometry `player` should see this frame, or an empty batch.
pub fn preview_for(player: PlayerId) -> crate::core::preview::Preview {
    with_sessions(|sessions| sessions.housing_preview(player).unwrap_or_default())
}

/// Forgets every session: for tests, and for leaving a world.
pub fn reset() {
    let mut guard = SESSIONS.lock().unwrap_or_else(|error| error.into_inner());
    *guard = None;
    FETCHING.store(true, Ordering::Relaxed);
    PENDING.lock().unwrap_or_else(|error| error.into_inner()).clear();
    GOLEMS.lock().unwrap_or_else(|error| error.into_inner()).clear();
}

fn depth(depth: PreviewDepth) -> WorldDepth {
    match depth {
        PreviewDepth::Tested => WorldDepth::Tested,
        PreviewDepth::ThroughWalls => WorldDepth::ThroughWalls,
    }
}

/// A right-click with the surveyor stake plants it on the face the player clicked.
///
/// The stake stands beside the clicked block on the face that was hit, which is where the Java item
/// puts it. Only the server side writes, so a single-player click plants once even though both sides
/// see the event.
pub fn use_item_on_block(host: HostSide, event: &mut UseItemOnBlockEvent<'_>) -> Flow {
    if event.phase != UsePhase::ItemBeforeBlock || event.click.side != LogicalSide::Server {
        return Flow::Continue;
    }
    let Some(held) = event.click.stack.map(|stack| stack.item()) else {
        return Flow::Continue;
    };
    let HostSide::Server(server) = host else {
        return Flow::Continue;
    };
    let Some(stake) = Identifier::parse(STAKE_ITEM)
        .ok()
        .and_then(|id| server.item_key(&id))
    else {
        return Flow::Continue;
    };
    if held != stake {
        return Flow::Continue;
    }

    let clicked = event.click.pos;
    // The stake stands on the face that was clicked: above it for the top, beside it for a side.
    let target = stake_position(clicked, event.click.hit.face);
    if !plant_stake(event.click.player, target) {
        // A full survey is not an error: the click simply does nothing.
        return Flow::Continue;
    }
    // The survey's stake is also a block in the world, written through the same table and bulk edit
    // a wall takes. A spawn the player never sees would be a survey of nothing. The click carries no
    // level handle, so the write waits for the next level tick, which also batches a burst of clicks.
    queue_write(
        BlockPos::new(target[0], target[1], target[2]),
        palette::SURVEYOR_STAKE,
    );
    *event.cancellation_result = InteractionResult::Success;
    Flow::Cancel
}

/// Where a stake planted on `clicked`'s `face` stands: the block next to it, as the Java item does.
///
/// A click on the top face plants above, a click on a side plants beside it, and a click that started
/// inside the block (`face` is `[0, 0, 0]`) plants in the block itself rather than a block up.
pub fn stake_position(clicked: [i32; 3], face: [i32; 3]) -> [i32; 3] {
    [
        clicked[0] + face[0],
        clicked[1] + face[1],
        clicked[2] + face[2],
    ]
}

/// Submits the local player's stake preview as world geometry, once per frame.
///
/// The hook is heard for every stage of the frame — sky, opaque, translucent, weather, then level —
/// and geometry submitted at the earlier ones would be drawn under the terrain it belongs on top of,
/// or twice. Only `AfterLevel` is the stage the API documents for custom world geometry.
pub fn render_level_stage(host: &dyn ClientHost, event: &mut RenderLevelStageEvent<'_>) {
    if event.stage != mod_api::RenderLevelStage::AfterLevel {
        return;
    }
    let Some(player) = host.local_player().map(|player| player.uuid()) else {
        return;
    };
    let preview = preview_for(player);
    if preview.is_empty() {
        return;
    }
    let quads: Vec<WorldQuad> = preview
        .quads
        .iter()
        .map(|quad| WorldQuad::new(quad.corners, quad.color, depth(quad.depth)))
        .collect();
    let lines: Vec<WorldLine> = preview
        .lines
        .iter()
        .map(|line| WorldLine::new(line.from, line.to, line.color, line.width, depth(line.depth)))
        .collect();
    if !quads.is_empty() {
        let _ = event.draw.quads(&quads);
    }
    if !lines.is_empty() {
        let _ = event.draw.lines(&lines);
    }
}

/// Each level tick: the client follows the Creative panel's terrain row, and a change remeshes the
/// loaded sections through `refresh_render_visibility`.
pub fn level_tick_post(host: HostSide, event: &mut LevelTickPostEvent) {
    match host {
        HostSide::Client(client) => crate::terrain::sync(client, crate::current_settings()),
        // The server side is where a golem's placements become blocks: one bulk edit per golem that
        // placed something this tick, never one write per cell.
        HostSide::Server(server) => tick_golems(server, event.level),
    }
}

/// Reads `GolemFetching` back whenever it changes, so the golems follow the rule the player set.
///
/// The hook hands the new value over, which is the reliable read: a rule's value belongs to the
/// level, and reading it back needs a level handle this hook does not carry.
pub fn game_rule_changed(_host: &mut dyn ServerHost, event: &mut GameRuleChangedEvent<'_>) {
    if event.rule != RULE_ID {
        return;
    }
    if let GameRuleValue::Bool(value) = event.value {
        FETCHING.store(value, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::preview::Preview;

    // The store is a process-wide static, so these checks live in one test that runs them in order.

    #[test]
    fn the_glue_tracks_players_stakes_and_the_rule() {
        reset();
        assert_eq!(stakes_down(1), 0);
        assert!(preview_for(1).is_empty(), "no stakes means no preview");

        assert!(plant_stake(1, [0, 64, 0]));
        assert!(plant_stake(1, [4, 64, 0]));
        assert!(plant_stake(1, [4, 64, 4]));
        assert_eq!(stakes_down(1), 3);
        assert_eq!(stakes_down(2), 0, "another player plants nothing");

        let preview: Preview = preview_for(1);
        assert!(!preview.is_empty(), "three stakes make an outline");
        assert!(preview.fits_budget());

        // The owner key is stable, distinct per player, and readable.
        assert_eq!(owner_key(1), owner_key(1));
        assert_ne!(owner_key(1), owner_key(2));
        assert!(owner_key(1).starts_with("player:"));

        // The rule starts enabled and follows the level once it is read.
        assert!(fetching_enabled());
        FETCHING.store(false, Ordering::Relaxed);
        assert!(!fetching_enabled());

        reset();
        assert_eq!(stakes_down(1), 0);
        assert!(fetching_enabled(), "a reset restores the rule's default");
    }

    #[test]
    fn writes_queue_until_a_level_tick_and_a_reset_clears_them() {
        reset();
        assert_eq!(pending_count(), 0, "nothing queued after a reset");
        queue_write(BlockPos::new(1, 64, 2), palette::STONE);
        queue_write(BlockPos::new(1, 65, 2), palette::SURVEYOR_STAKE);
        assert_eq!(pending_count(), 2, "both clicks wait for the tick");
        reset();
        assert_eq!(pending_count(), 0);
    }

    #[test]
    fn golems_are_enlisted_and_forgotten() {
        reset();
        assert_eq!(golem_count(), 0);
        enlist(Golem::spawned_by_player(
            7,
            crate::core::golem::GolemKind::Iron,
            owner_key(1),
            BlockPos::new(0, 64, 0),
        ));
        assert_eq!(golem_count(), 1);
        assert!(GOLEMS
            .lock()
            .unwrap()
            .first()
            .map(|golem| golem.is_minion())
            .unwrap_or(false), "a player's golem may build");
        forget_golems();
        assert_eq!(golem_count(), 0);
        reset();
    }

    #[test]
    fn a_stake_stands_on_the_face_that_was_clicked() {
        // Top, bottom and the four sides, then a click that started inside the block.
        assert_eq!(stake_position([3, 64, 5], [0, 1, 0]), [3, 65, 5]);
        assert_eq!(stake_position([3, 64, 5], [0, -1, 0]), [3, 63, 5]);
        assert_eq!(stake_position([3, 64, 5], [1, 0, 0]), [4, 64, 5]);
        assert_eq!(stake_position([3, 64, 5], [-1, 0, 0]), [2, 64, 5]);
        assert_eq!(stake_position([3, 64, 5], [0, 0, 1]), [3, 64, 6]);
        assert_eq!(stake_position([3, 64, 5], [0, 0, -1]), [3, 64, 4]);
        assert_eq!(stake_position([3, 64, 5], [0, 0, 0]), [3, 64, 5]);
    }

    #[test]
    fn preview_depths_map_onto_the_draw_context_depths() {
        assert_eq!(depth(PreviewDepth::Tested), WorldDepth::Tested);
        assert_eq!(depth(PreviewDepth::ThroughWalls), WorldDepth::ThroughWalls);
    }
}
