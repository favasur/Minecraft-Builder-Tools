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
//! * `GolemFetching` is read from the level once per tick and cached for the golem code.
//!
//! What is not, and why, is in the README: applying a golem's actions needs a block palette that
//! maps the core's opaque `BlockId`s onto the host's block states, and that table belongs to the
//! tool layer that selects a block in the first place.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use mod_api::{
    ClientHost, Flow, GameRuleChangedEvent, GameRuleValue, HostSide, Identifier, InteractionResult,
    LogicalSide, RenderLevelStageEvent, ServerHost, UseItemOnBlockEvent, UsePhase, WorldDepth,
    WorldLine, WorldQuad,
};

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

/// The wall block a session starts with. Real builds take their block from the tool in hand; until
/// that layer exists, this stands in so a session is always valid.
const PLACEHOLDER_WALL: BlockId = BlockId::new(0);

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

/// The preview geometry `player` should see this frame, or an empty batch.
pub fn preview_for(player: PlayerId) -> crate::core::preview::Preview {
    with_sessions(|sessions| sessions.housing_preview(player).unwrap_or_default())
}

/// Forgets every session: for tests, and for leaving a world.
pub fn reset() {
    let mut guard = SESSIONS.lock().unwrap_or_else(|error| error.into_inner());
    *guard = None;
    FETCHING.store(true, Ordering::Relaxed);
}

fn depth(depth: PreviewDepth) -> WorldDepth {
    match depth {
        PreviewDepth::Tested => WorldDepth::Tested,
        PreviewDepth::ThroughWalls => WorldDepth::ThroughWalls,
    }
}

/// A right-click with the surveyor stake plants it where the player clicked.
///
/// The stake stands on top of the clicked block, which is where the Java item puts it. Only the
/// server side writes, so a single-player click plants once even though both sides see the event.
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
    // The stake stands on top of the block that was clicked.
    if !plant_stake(event.click.player, [clicked[0], clicked[1] + 1, clicked[2]]) {
        // A full survey is not an error: the click simply does nothing.
        return Flow::Continue;
    }
    *event.cancellation_result = InteractionResult::Success;
    Flow::Cancel
}

/// Submits the local player's stake preview as world geometry.
pub fn render_level_stage(host: &dyn ClientHost, event: &mut RenderLevelStageEvent<'_>) {
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
    fn preview_depths_map_onto_the_draw_context_depths() {
        assert_eq!(depth(PreviewDepth::Tested), WorldDepth::Tested);
        assert_eq!(depth(PreviewDepth::ThroughWalls), WorldDepth::ThroughWalls);
    }
}
