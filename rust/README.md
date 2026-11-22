# Builder Tools — Horizon (Rust) port

A Rust port of the Builder Tools Java mod, targeting **Minecraft: Java Edition 26.3 on Horizon**
(the Rust rewrite of the game) through its mod API. Horizon has its own mod system: Java jars do
not run there, and a mod is a `.hmod` holding `horizon-mod.toml`, this `Cargo.toml` and `src/`.

Pinned build: `2b77287de099c9316278ba7ac4d83590fdb7ef30` (game 26.3, mod API 0.2). The SDK is
published per build as a signed release and is fetched into `.horizon/sdk/`; `.horizon/` is
gitignored. Re-fetch it with `cargo horizon-mod check` (or download the build's `api-source.tar.xz`
release asset and unpack it so that `.horizon/sdk/api/crates/horizon-mod-api` exists).

## What is done

The loader-independent half of the mod is ported and covered by unit tests (`cargo test`, 61 tests):

| Rust | Ported from | State |
|---|---|---|
| `src/core/pos.rs` — `BlockPos`, `BlockBox`, region predicates | `SelectionManager`, `SelectionStore.Region`, `BlockPos.betweenClosed` | done |
| `src/core/ops.rs` — `/set`, `/clear`, `/replace`, `/walls`, `/outline`, `/hollow`, `/faces`, `/overlay`, `/center`, `/move`, `/stack` | `server/BuilderCommand.java` | done |
| `src/core/history.rs` — 50-operation undo/redo | `server/UndoStore.java` | done |
| `src/core/clipboard.rs` — copy/paste | `server/ClipboardStore.java`, `copySelection`/`paste` | done |
| `src/core/arch.rs` — voussoir arch radius, arc split, fan fallback, face frame, wedge corners, `RegionArch::Bezier` | `util/ArchGeometry.java`, `util/FaceFrame.java` | done |
| `src/core/bezier.rs` — quadratic Bézier band, arc-length split, extension, surface | `util/BezierGeometry.java` | done |
| `src/core/ellipse.rs` — closed ellipse ring, arc-length boundaries, curvature guard, surface | `util/EllipseGeometry.java` | done |
| `src/core/wedge.rs` — voussoir tessellation, tile-splitting UVs, collision triangles | `ArchGeometry.WedgeFace`, `wedgeFaces`, `addFaceQuads`, `wedgeTriangles` | done |
| `src/commands.rs` — the command set's parsing and direction words | `server/BuilderCommand.java` | done |
| `src/lib.rs` — the `Hooks` root | `BuilderToolsMod` | scaffold only |

The region ops, all three curved shapes and the command set are covered by tests: `cargo test` runs
61 of them, including the shape identities (voussoir tiling, ellipse closure, 1 m thickness), the
tessellation invariants (34 quads and 68 triangles per voussoir, tile-split UV phases, face normals
on the expected axis) and the numerical checks in `core::fast`.

`RegionArch::BezierBand` was a placeholder that reported the Bézier gap; the band is now built by
`core::bezier` and the variant carries the real geometry (`Bezier { arch, count, span, center }`), so
wide-wall arching and elliptical rings are complete in the core.

## What is not done yet

`src/lib.rs` implements `Hooks` with no methods wired, so the mod currently loads and does nothing
in game. Everything below is still Java-only:

* **Hook wiring** — `use_item_on_block`, `left_click_block`, `right_click_block`, `break_block`,
  `attack_entity`, `visuals`, `screen_render_post`, `register_commands` and `run_mod_command` are
  implemented by the Java mod's `ClientEvents`, `BuilderCommand` and `ServerEvents`.
* **Applying edits** — the port computes change batches but does not send them to the world.
  Horizon's route is `ServerHost::edit_blocks(dimension, &BlockEdit)`; building a `BlockEdit`
  palette and runs, plus resolving block arguments via `block_state_id`, is the next piece.
* **Per-player state** — `CommandSource::Player { player, .. }` gives the `PlayerRef` to key the
  selections, clipboards and histories on.
* **Rotated / off-grid blocks** — `OffGridBlockEntity`, `OffGridTransform`, `RotatedBlockRenderer`,
  `RotatedBlockModel`, `RotatedBlockTriangles`, `RotatedSlabPlacement`. The Java mod implements
  these with mixins into the client render pipeline and a display entity; Horizon exposes render
  hooks (`visuals`, `frame`) and native draw helpers (`draw_quad`, `draw_cube`), which the port has
  not used yet.
* **Entity tool** — `EntityToolScreen`, `EntityDragState`, `EntityRotateState`, and the server-side
  `moveEntity`/`duplicateEntity`/`freezeEntity`.
* **Brushes** — `paint`, `scatter`, `smooth` and their preview sphere.
* **Creative settings screen** — `CreativeSettingsScreen` and `builder settings`. Horizon's mod-owned
  screens and drawing are still on its roadmap.
* **Smooth terrain**, sounds, and the mod's network packets.
* **Packing the wedge surface into draw calls** (`wedgeQuads`). `wedge.rs` produces the tessellated
  quads with their tile-split UVs and the collision triangles; turning a `TileQuad` plus a
  `FaceKind` into a vertex buffer needs Horizon's block-sprite access, which the port has not wired
  yet. The geometry and UV logic itself is ported and tested.

See [OPTIMIZATIONS.md](OPTIMIZATIONS.md) for the measured comparison of this mirrored
implementation against faster alternatives (`core::fast`, `examples/bench.rs`).

## Deliberate differences from the Java mod

* **Redo works.** `BlockChange` records the state before *and* after a change; the Java record keeps
  only the pre-change state, so its `/redo` re-applies the old blocks. `/undo`/`/redo` here restore
  the two sides respectively.
* **`/contract` shrinks.** The Java `/contract` negates the direction and then moves the *min* face,
  so `/contract up` grows the region downward. The port pulls the face the direction points at,
  which is what the README describes (a note in `BlockBox::contract` records this).
* **`/outline` is the whole shell,** matching the shipped Java behaviour (`x == min || x == max ||
  y == … || z == …`) rather than the README's "12 edges".
* **`/replace <from> <to>` matches the block, not the block state,** as the Java `getBlock()`
  comparison does.

## Build

```bash
cd rust
cargo test                            # the ported logic; no game needed
cargo check                           # compiles the mod against the pinned SDK
cargo run --release --example bench   # the measurements quoted in OPTIMIZATIONS.md
```

Packaging and running in game (`cargo horizon-mod build --wasm`, or the launcher's
`native-build`) needs the `cargo-horizon-mod` tool and a Horizon instance.
