//! Builder Tools for Minecraft: Java Edition 26.3 on Horizon.
//!
//! This crate is the Rust port of the Java mod `net.buildertools`. It is split in two layers:
//!
//! * [`crate::core`] — the loader-independent logic (selection regions, the WorldEdit-style region
//!   operations, undo/redo, the clipboard, the arch geometry and command parsing). It has no
//!   dependency on the game and is covered by `cargo test`.
//! * the [`Hooks`] implementation below — the Horizon glue, where the port hands that logic to the
//!   game.
//!
//! The port targets the mod API of the pinned build in `horizon-mod.toml`; the SDK it builds
//! against lives in `.horizon/sdk/`, fetched from the `sdk-<build id>` release of that build.

mod commands;
mod core;
mod hooks;
pub mod ui;


/// Alternative implementations measured against the mirrored ones (see `OPTIMIZATIONS.md`).
pub mod fast {
    pub use crate::core::fast::*;
}

pub use commands::{Parsed, USAGES, direction, horizontal_look, parse};
pub use core::arch::{
    ARC_SUBDIVISIONS, ArchBlockData, ArchKind, ArchResult, DEPTH_HALF, Face, FaceFrame, MIN_RISE,
    RegionArch, Vec3, block_data, box_center, cell_extent, compute_arch_in_plane, face_center,
    fan_block_data, region_arch,
};
pub use core::bezier::{BezierArch, BezierBlockData, extend as extend_bezier};
pub use core::claim::{
    Claim, ClaimError, ClaimId, ClaimRequest, ClaimStore, TerritoryKind, VerticalExtent,
    polygons_intersect,
};
pub use core::golem::{
    GAMERULE, GAMERULE_CATEGORY, GAMERULE_DISPLAY, Golem, GolemAction, GolemKind, GolemProfile,
    GolemRules, SpawnOrigin,
};
pub use core::map::{
    ClaimFeed, ClaimMarker, ClaimOverlay, OTHER_FILL, OTHER_OUTLINE, feed as claim_feed,
    overlay as claim_overlay, style_for as claim_style,
};
pub use core::panel::{Panel, PanelRow, RowKind};
pub use core::plan::{BuildPlan, PlanStep};
pub use core::sessions::{PlayerId, PlayerSession, Sessions};
pub use core::settings::{BuilderSettings, Setting, SettingError};
pub use core::clipboard::Clipboard;
pub use core::edit::{BlockEdit, BlockRun};
pub use core::ellipse::{
    EllipseBlockData, EllipseResult, MIN_CURVATURE, RegionEllipse, build_ellipse, region_ellipse,
};
pub use core::history::{History, MAX_OPS};
pub use core::ops::{
    BlockChange, MAX_BLOCKS, MAX_DISTANCE, center, clear, faces, fill, hollow, move_region, outline,
    overlay, replace_all, replace_where, stack, walls,
};
pub use core::path::{
    PathBand, PathBands, PathStyle, Sample, paint as paint_path, samples as path_samples,
    sweep as path_sweep,
};
pub use core::polygon::{
    Border, Hatch, Polygon, Territory, paint as paint_territory,
};
pub use core::mesher::{
    DensityGrid, MeshQuad, SectionMesh, SmoothSpec, smooth as smooth_terrain, smooth_heights,
    surface_nets,
};
pub use core::offgrid::{
    GRID_EPSILON, OffGridTransform, OrientedBox, UnitBox, cells_on_ray,
    raycast as raycast_off_grid,
};
pub use core::pos::{BlockBox, BlockPos};
pub use core::preview::{
    FILL_YELLOW, OUTLINE_WIDTH, OUTLINE_YELLOW, Preview, PreviewDepth, PreviewLine, PreviewQuad,
    PreviewStyle, polygon_band, polygon_preview, polygon_ring,
};
pub use core::survey::{Angle, MAX_STAKES, Span, Stake, StakeKind, Survey, Turn};
pub use core::wall::{
    Housing, MIN_COURSE, WallSpec, build as build_wall, preview as wall_preview,
};
pub use core::wedge::{FaceKind, TileQuad, WedgeFace, outward_normal, wedge_triangles};
pub use hooks::{fetching_enabled, plant_stake, reset, stakes_down};
pub use ui::{current_settings, edit_settings};
pub use core::world::{BlockId, World};

use mod_api::{
    ClientHost, Flow, GameRuleChangedEvent, Hooks, HostSide, Registrar, RegistrationContext,
    RenderLevelStageEvent, ScreenCharTypedPreEvent, ScreenKeyPressedPreEvent,
    ScreenMouseButtonPressedPreEvent, ScreenMouseButtonReleasedPreEvent, ScreenMouseDraggedPreEvent,
    ScreenRenderPostEvent, ServerHost, UseItemOnBlockEvent,
};
use std::sync::OnceLock;

/// The mod's root type, as `#[mod_api::horizon_mod]` requires.
#[derive(Default)]
pub struct BuilderTools;

/// The registered `buildertools:golem_fetching` rule, kept so the minion glue can read the value
/// back with `LevelRef::game_rule_bool` instead of looking the rule up by name every tick.
static GOLEM_FETCHING: OnceLock<mod_api::BoolGameRuleKey> = OnceLock::new();

/// The `/gamerule GolemFetching` key, once registration has run.
pub fn golem_fetching_key() -> Option<mod_api::BoolGameRuleKey> {
    GOLEM_FETCHING.get().copied()
}

/// The code half of the mod.
///
/// Blocks and items are not registered here: a package's own `assets/` and `data/` trees load
/// automatically with the mod, so content is declared as files (see `CONTENT.md`). What does go
/// through `Hooks::register` is everything the API cannot express as a file — here the game rule
/// that decides how a golem gets its blocks.
#[mod_api::horizon_mod]
impl Hooks for BuilderTools {
    fn register(&self, registrar: &mut dyn Registrar, _context: &RegistrationContext) {
        // `/gamerule GolemFetching` is written lower-case and namespaced in game, as every gamerule
        // is; the sidebar and the docs keep the readable name.
        let _ = registrar.game_rule_category("golems");
        if let Ok(key) = registrar.game_rule_bool(GAMERULE, "buildertools:golems", true) {
            let _ = GOLEM_FETCHING.set(key);
        }
    }

    // The Creative settings panel: drawn over the creative inventory, and taking only the input that
    // lands on it. See `ui`.

    fn screen_render_post(&self, host: &dyn ClientHost, event: &mut ScreenRenderPostEvent<'_>) {
        ui::draw(host, event);
    }

    fn screen_mouse_button_pressed_pre(
        &self,
        host: &dyn ClientHost,
        event: &mut ScreenMouseButtonPressedPreEvent<'_>,
    ) -> Flow {
        ui::mouse_pressed(host, event)
    }

    fn screen_mouse_button_released_pre(
        &self,
        _host: &dyn ClientHost,
        event: &mut ScreenMouseButtonReleasedPreEvent<'_>,
    ) -> Flow {
        ui::mouse_released(event)
    }

    fn screen_mouse_dragged_pre(
        &self,
        host: &dyn ClientHost,
        event: &mut ScreenMouseDraggedPreEvent<'_>,
    ) -> Flow {
        ui::mouse_dragged(host, event)
    }

    fn screen_key_pressed_pre(
        &self,
        host: &dyn ClientHost,
        event: &mut ScreenKeyPressedPreEvent<'_>,
    ) -> Flow {
        ui::key_pressed(host, event)
    }

    fn screen_char_typed_pre(
        &self,
        host: &dyn ClientHost,
        event: &mut ScreenCharTypedPreEvent<'_>,
    ) -> Flow {
        ui::char_typed(host, event)
    }

    // The game-facing glue: planting stakes, drawing their preview, reading the golem rule. See
    // `hooks`.

    fn use_item_on_block(&self, host: HostSide, event: &mut UseItemOnBlockEvent<'_>) -> Flow {
        hooks::use_item_on_block(host, event)
    }

    fn render_level_stage(&self, host: &dyn ClientHost, event: &mut RenderLevelStageEvent<'_>) {
        hooks::render_level_stage(host, event)
    }

    fn game_rule_changed(&self, host: &mut dyn ServerHost, event: &mut GameRuleChangedEvent<'_>) {
        hooks::game_rule_changed(host, event);
    }
}
