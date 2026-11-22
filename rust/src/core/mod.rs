//! Loader-independent logic ported from the Java mod.
//!
//! Everything in this module is plain arithmetic over block coordinates and block ids: it has no
//! dependency on the Horizon API, so it compiles and is unit-tested with `cargo test` alone.

pub mod arch;
pub mod bezier;
pub mod claim;
pub mod clipboard;
pub mod edit;
pub mod ellipse;
pub mod fast;
pub mod golem;
pub mod history;
pub mod map;
pub mod mesher;
pub mod offgrid;
pub mod ops;
pub mod panel;
pub mod path;
pub mod plan;
pub mod polygon;
pub mod pos;
pub mod preview;
pub mod sessions;
pub mod settings;
pub mod survey;
pub mod wall;
pub mod wedge;
pub mod world;
