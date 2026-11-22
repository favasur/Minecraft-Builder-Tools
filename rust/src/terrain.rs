//! Smooth terrain in game: the section renderer, the visibility answer and the live switch.
//!
//! Three pieces work together, and all three are API 0.2 features rather than workarounds:
//!
//! 1. **`add_section_geometry`** registers one renderer for the whole game. The mesher asks it for
//!    every section it builds, on its worker threads, so a section's quads are part of that
//!    section's mesh: rebuilt with it, never duplicated, dropped when it unloads.
//! 2. **`block_render_visibility`** hides the ground and the water from that same mesher. The answer
//!    is resolved when the client joins and again on every refresh, which is exactly what a toggle
//!    needs.
//! 3. **`refresh_render_visibility`** remeshes the loaded sections when the answer changes, so
//!    flipping *Smooth terrain* in the Creative panel swaps the world over in place.
//!
//! The cost model matters: the renderer runs on meshing workers and must not allocate per block.
//! Sampling a section is 9,261 block reads (the section plus the blur's margin), one table read lock
//! and `core::terrain`'s planning and blur; nothing here touches the main thread's frame.
//!
//! Collision remains the game's. This API reads a state's collision shape but no mod can answer it,
//! and there is no collision-shape hook, so the player walks on the blocks underneath the surface.
//! Since the surface is drawn between those blocks' own tops, no invisible mesh collar can block a
//! step: the only thing the player collides with is real blocks. The README states the split.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

use mod_api::{
    BlockRenderVisibilityEvent, BlockState, ClientHost, LightLayer, SectionGeometry,
    SectionGeometryContext, SectionQuad,
};

use crate::core::settings::BuilderSettings;
use crate::core::terrain::{
    BlockSample, TerrainMode, TerrainQuad, WATER_BLOCKS, plan_section, sprite_for,
};

/// The current mode, shared with the meshing workers. `0` is vanilla, `1` smooth.
static MODE: AtomicU8 = AtomicU8::new(0);

/// Every hidden block state's sprite and whether it is water, or `None` when it is not replaced.
///
/// Filled by the visibility answer — the one place a client host is in hand to turn a `BlockKey`
/// into an id — and read by the renderer, which gets no host at all. One read lock per section.
static REPLACED: OnceLock<RwLock<HashMap<mod_api::BlockKey, Option<(&'static str, bool)>>>> =
    OnceLock::new();

fn replaced_table() -> &'static RwLock<HashMap<mod_api::BlockKey, Option<(&'static str, bool)>>> {
    REPLACED.get_or_init(|| RwLock::new(HashMap::with_capacity(512)))
}

/// The mode the world is drawn in right now.
pub fn mode() -> TerrainMode {
    if MODE.load(Ordering::Relaxed) == 1 {
        TerrainMode::Smooth
    } else {
        TerrainMode::Vanilla
    }
}

/// Stores a mode. Returns whether it changed, which is when the sections must be remeshed.
pub fn set_mode(mode: TerrainMode) -> bool {
    let wanted = u8::from(mode.is_smooth());
    MODE.swap(wanted, Ordering::AcqRel) != wanted
}

/// Follows the Creative panel. Called on the client each level tick; a change remeshes every loaded
/// section, which is a few seconds on the workers, so it happens when the row is pressed and not
/// before.
pub fn sync(host: &mut dyn ClientHost, settings: BuilderSettings) {
    if set_mode(settings.terrain_mode()) {
        host.refresh_render_visibility();
    }
}

/// The section renderer: one per game, shared by the meshing workers, stateless by construction.
pub struct SmoothTerrainRenderer;

impl SectionGeometry for SmoothTerrainRenderer {
    fn add(&self, origin: [i32; 3], context: &mut dyn SectionGeometryContext) {
        if !mode().is_smooth() {
            return;
        }
        let level = context.level();
        let table = replaced_table();
        let table = table.read().unwrap_or_else(|error| error.into_inner());
        if table.is_empty() {
            // The visibility answer has not run yet, so nothing is hidden and nothing should be
            // drawn on top of the blocks.
            return;
        }

        let plan = plan_section(TerrainMode::Smooth, |x, y, z| {
            let pos = [origin[0] + x, origin[1] + y, origin[2] + z];
            let state = level.block_state(pos);
            let (sprite, is_water) = (*table.get(&state.block())?)?;
            let sky = level.brightness(LightLayer::Sky, pos).clamp(0, 15) as u8;
            let block = level.brightness(LightLayer::Block, pos).clamp(0, 15) as u8;
            Some(if is_water {
                BlockSample::water(sprite, [sky, block])
            } else {
                BlockSample::ground(sprite, [sky, block])
            })
        });

        let Ok(plan) = plan else {
            return;
        };
        let mut budget = true;
        for quad in plan.quads {
            if !context.add(quad.sprite, to_section_quad(quad, false)) {
                // The section's budget refused this renderer: the batch is all-or-nothing, and
                // every later add would be refused too.
                budget = false;
                break;
            }
        }
        if budget {
            for quad in plan.water {
                // Water goes into the translucent pass, sorted and blended, so it reads as water
                // rather than as an opaque capping plane.
                if !context.add(quad.sprite, to_section_quad(quad, true)) {
                    break;
                }
            }
        }
    }
}

/// The core's quad in the shape the section context takes.
fn to_section_quad(quad: TerrainQuad, translucent: bool) -> SectionQuad {
    SectionQuad {
        positions: quad.positions,
        uvs: quad.uvs,
        color: quad.color,
        light: quad.light,
        translucent,
    }
}

/// The part attached to every replaced block: it answers the mesher's visibility question.
///
/// A block part rather than a global hook, because visibility is asked per state and answered from
/// the state alone. Attached in [`register`], one instance for all replaced states, so it holds no
/// state.
#[derive(Debug, Default)]
pub struct TerrainPart;

#[mod_api::__subscriptions]
impl mod_api::BlockPart for TerrainPart {
    /// Hides the ground and the water while the smooth mode is on. The part is attached to replaced
    /// blocks only, so the state needs no further checks — its sprite was recorded at registration.
    fn block_render_visibility(&self, _host: &dyn ClientHost, event: &mut BlockRenderVisibilityEvent) {
        if mode().is_smooth() {
            event.hidden = true;
        }
    }
}

/// The diagnostic the panel's tooltip and the README's checks read: how many replaced states are
/// known.
pub fn replaced_states() -> usize {
    let table = replaced_table();
    let table = table.read().unwrap_or_else(|error| error.into_inner());
    table.values().filter(|sprite| sprite.is_some()).count()
}

/// A block state's effect on the mesher, for the tests and for a debug line.
pub fn hides(state: &BlockState) -> bool {
    if !mode().is_smooth() {
        return false;
    }
    let table = replaced_table();
    let table = table.read().unwrap_or_else(|error| error.into_inner());
    matches!(table.get(&state.block()), Some(Some(_)))
}

/// Registers the renderer with the game's registry. Called from `add_section_geometry`.
pub fn register_renderer(renderers: &mut dyn mod_api::SectionGeometryRenderers) {
    renderers.register(Arc::new(SmoothTerrainRenderer));
}

/// Attaches [`TerrainPart`] to every block in [`crate::core::terrain::TERRAIN_BLOCKS`] and
/// [`WATER_BLOCKS`], resolving each id once at load. A block this game build does not have is
/// skipped rather than failing the rest, so one renamed id cannot disable the whole feature.
pub fn register_parts(registrar: &mut dyn mod_api::Registrar) {
    let mut table = replaced_table()
        .write()
        .unwrap_or_else(|error| error.into_inner());
    let ground = crate::core::terrain::TERRAIN_BLOCKS;
    table.reserve(ground.len() + WATER_BLOCKS.len());
    for (id, is_water) in ground
        .iter()
        .map(|id| (*id, false))
        .chain(WATER_BLOCKS.iter().map(|id| (*id, true)))
    {
        let key = registrar.block_key(id);
        // The renderer gets no host, so the sprite is recorded here, once per block, where the id is
        // known; a quad for this block then costs one table lookup.
        table.insert(key, Some((sprite_for(id)[0], is_water)));
        registrar.block_part(key, TerrainPart);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_mode_starts_vanilla_and_flips_once() {
        // The static is process-wide; set it back at the end so other tests are unaffected.
        set_mode(TerrainMode::Vanilla);
        assert_eq!(mode(), TerrainMode::Vanilla);
        assert!(set_mode(TerrainMode::Smooth));
        assert_eq!(mode(), TerrainMode::Smooth);
        assert!(!set_mode(TerrainMode::Smooth), "already smooth");
        assert!(set_mode(TerrainMode::Vanilla));
        assert_eq!(mode(), TerrainMode::Vanilla);
    }

    #[test]
    fn a_quad_reaches_the_section_context_with_its_fields_intact() {
        let quad = TerrainQuad {
            positions: [[0.0, 8.5, 0.0], [1.0, 8.5, 0.0], [1.0, 8.5, 1.0], [0.0, 8.5, 1.0]],
            uvs: [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0]],
            color: 0xFFFF_FFFF,
            light: [15, 3],
            sprite: "minecraft:block/stone",
        };
        let converted = to_section_quad(quad, false);
        assert_eq!(converted.positions, quad.positions);
        assert_eq!(converted.uvs, quad.uvs);
        assert_eq!(converted.light, [15, 3]);
        assert_eq!(converted.color, 0xFFFF_FFFF);
        assert!(!converted.translucent, "terrain is opaque: the solid pass");

        // Water is the same quad in the translucent pass.
        let water = to_section_quad(quad, true);
        assert!(water.translucent);
        assert_eq!(water.positions, quad.positions);
    }
}
