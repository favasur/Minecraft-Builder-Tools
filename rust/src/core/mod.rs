//! Loader-independent logic ported from the Java mod.
//!
//! Everything in this module is plain arithmetic over block coordinates and block ids: it has no
//! dependency on the Horizon API, so it compiles and is unit-tested with `cargo test` alone.

pub mod arch;
pub mod bezier;
pub mod clipboard;
pub mod ellipse;
pub mod fast;
pub mod history;
pub mod ops;
pub mod pos;
pub mod wedge;
pub mod world;
