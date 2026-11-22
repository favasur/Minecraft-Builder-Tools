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
pub use core::clipboard::Clipboard;
pub use core::ellipse::{
    EllipseBlockData, EllipseResult, MIN_CURVATURE, RegionEllipse, build_ellipse, region_ellipse,
};
pub use core::history::{History, MAX_OPS};
pub use core::ops::{
    BlockChange, MAX_BLOCKS, MAX_DISTANCE, center, clear, faces, fill, hollow, move_region, outline,
    overlay, replace_all, replace_where, stack, walls,
};
pub use core::pos::{BlockBox, BlockPos};
pub use core::wedge::{FaceKind, TileQuad, WedgeFace, outward_normal, wedge_triangles};
pub use core::world::{BlockId, World};

use mod_api::Hooks;

/// The mod's root type, as `#[mod_api::horizon_mod]` requires.
#[derive(Default)]
pub struct BuilderTools;

#[mod_api::horizon_mod]
impl Hooks for BuilderTools {}
