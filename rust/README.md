# Builder Tools — Horizon (Rust) port

A Rust port of the Builder Tools Java mod, targeting **Minecraft: Java Edition 26.3 on Horizon**
(the Rust rewrite of the game) through its mod API. Horizon has its own mod system: Java jars do
not run there, and a mod is a `.hmod` holding `horizon-mod.toml`, this `Cargo.toml` and `src/`.

Pinned build: `2b77287de099c9316278ba7ac4d83590fdb7ef30` (game 26.3, mod API 0.2). The SDK is
published per build as a signed release and is fetched into `.horizon/sdk/`; `.horizon/` is
gitignored. Re-fetch it with `cargo horizon-mod check` (or download the build's `api-source.tar.xz`
release asset and unpack it so that `.horizon/sdk/api/crates/horizon-mod-api` exists).

## What is done

The loader-independent half of the mod is ported and covered by unit tests (`cargo test`, 248 tests):

| Rust | Ported from | State |
|---|---|---|
| `src/core/pos.rs` — `BlockPos`, `BlockBox`, region predicates | `SelectionManager`, `SelectionStore.Region`, `BlockPos.betweenClosed` | done |
| `src/core/ops.rs` — `/set`, `/clear`, `/replace`, `/walls`, `/outline`, `/hollow`, `/faces`, `/overlay`, `/center`, `/move`, `/stack` | `server/BuilderCommand.java` | done |
| `src/core/history.rs` — 50-operation undo/redo, stored packed (15.6 MiB per player at the limits) | `server/UndoStore.java` | done |
| `src/core/clipboard.rs` — copy/paste | `server/ClipboardStore.java`, `copySelection`/`paste` | done |
| `src/core/edit.rs` — a change batch packed into palette + column runs for one bulk edit | `ServerHost::edit_blocks` (Horizon's batch route) | done |
| `src/core/path.rs` — linear objects: a Catmull–Rom path swept with a width, thickness and per-band block slots (road, path, fence) | no Java source; modelled on map-editor linear objects | done |
| `src/core/polygon.rs` — territorial objects: arbitrary polygon with border band, fill and diagonal hatching, stacked to a depth | no Java source; modelled on map-editor area objects | done |
| `src/core/preview.rs` — polygonal territory previews: a semi-transparent yellow area with a bright yellow outline, merged per row | no Java source | done |
| `src/core/wall.rs` — Wall Polygons and Housing Surveyor Stakes: battered walls along any closed outline, built on Enter | no Java source | done |
| `src/core/survey.rs` — Surveyor Stakes: planted anchors giving an A–B span, a bearing, an angle with turn direction, a vertical extent, path points and a polygon; tagged linear, territory or housing | no Java source | done |
| `src/core/claim.rs` — Private Territory: owned polygon + `startY`/`endY` band (whole world height by default), 3D overlap rules, build permission, revision counter | no Java source | done |
| `src/core/map.rs` — every claim shown to **every** player: a feed (owner, name, area, band) plus yellow/amber overlay geometry | no Java source; like FTB Chunks / Open Parties and Claims | done |
| `src/core/plan.rs` — a build plan: an outline ordered band by band, with the cells the owner may not build dropped | no Java source | done |
| `src/core/golem.rs` — Iron and Copper Golem minions: player-spawned only, one block per cadence, fetching per `/gamerule GolemFetching` | no Java source | done |
| `src/core/settings.rs` — one `BuilderSettings` value set (wall, golem, path, territory, reach) with ranges enforced on every write, and the conversions into `WallSpec`, `GolemRules`, `PathStyle` and `Territory` | `client/settings/BuilderSettings.java` | done |
| `src/core/panel.rs` — the Creative panel's layout: right-hand column, one row per setting, field or button | `CreativeSettingsScreen.java` (layout) | done |
| `src/ui.rs` — the panel on the client: drawn over the creative inventory, widgets and input | `CreativeSettingsScreen.java` (widgets) | done, not yet seen in game |
| `src/core/sessions.rs` — per-player state: each UUID's own survey, housing tool, settings and golems, plus the shared claim store, its build-rights check and the owner-only release | `SelectionStore`/`UndoStore` per player, `ClaimManager` | done |
| `src/core/offgrid.rs` — offset rotatable blocks: yaw/pitch/offset transform with its inverse, snapping, the oriented box a turned block occupies, ray-vs-box, the voxel walk and the nearest-hit raycast | `OffGridTransform.java`, `RotatedBlockTriangles.java`, `collision/RotatedCollisionProvider.java`, `util/FreeBlockRaycast.java` | geometry done; collision and rendering limited by API 0.2 (below) |
| `src/core/mesher.rs` — smooth meshed terrain: surface nets over a 16³ density field (one vertex per straddling cell, one quad per sign-changing lattice edge) plus the smoothing tool that averages a column's surface height and fills or cuts the difference | bundled `io.github.favasur.smoothterrain` (geometry half), `item/SmoothToolItem.java` | done |
| `src/core/logs.rs` — rounded logs: a cylindrical trunk whose lower rim is dropped onto the *meshed* terrain sample by sample (so a tree on a slope follows the hill), the cell body that starts at the ground per column, a skirt so no gap shows, and `mesh_height`/`HeightIndex` for sampling the smooth surface | no Java source: new, built on `mesher` | done |
| `src/core/brush.rs` — the brush tools: a radius-4 sphere of paint, a scatter over air that sits on solid ground, and the smoothing brush that levels a disc to its mean height using each column's own surface block | `BuilderServerHandler.paint`, `scatter`, `smooth`, `spherePositions` | done |
| `src/core/handles.rs` — selection face-handle dragging: a drag plane fixed at grab time, the face snapped to the block grid, click-without-drag nudge, and the stretch flag | `client/HandleDragState.java` | done |
| `src/core/entities.rs` — the Entity Tool's free-move drag (plane hit, grid snap, surface lock, dead zone) and its rotate mode (wrapped yaw accumulation, head mode) | `EntityDragState.java`, `EntityRotateState.java` | done |
| `src/core/ruler.rs` — the Ruler's span/diagonal/volume/midpoint reading and the Laser's change-only target tracking | `selection/RulerState.java`, `selection/LaserState.java` | done |
| `src/core/terrain.rs` — smooth terrain planning: which blocks are ground, a 16³ section as a blurred density field (rounded sides), its surface and its water surface as section quads with world-tiled UVs, light and sprites; `TerrainMode` toggle | `SmoothTerrainWorldRules.smoothness()`, bundled smooth terrain (policy half) | done |
| `src/terrain.rs` — smooth terrain in game: the `add_section_geometry` renderer, the `block_render_visibility` block part for ground, and the live switch through `refresh_render_visibility` | `SmoothTerrainTogglePacket`, bundled mod's render hooks | done, not yet seen in game |
| `src/hooks.rs` — the game-facing glue: a surveyor-stake right-click plants a housing stake in that player's session, the local player's yellow preview is submitted as world geometry every frame, and `GolemFetching` is read back from its change event | `ClientEvents`, `ServerEvents` (the parts above) | done, not yet seen in game |
| `data/`, `assets/` — package content: `buildertools:surveyor_stake`, a sign-templated block (player-facing `rotation`/`facing`, thin plate collision) with a post-only model and its default block item | API 0.2 content files, see [CONTENT.md](CONTENT.md) | done |
| `src/core/arch.rs` — voussoir arch radius, arc split, fan fallback, face frame, wedge corners, `RegionArch::Bezier` | `util/ArchGeometry.java`, `util/FaceFrame.java` | done |
| `src/core/bezier.rs` — quadratic Bézier band, arc-length split, extension, surface | `util/BezierGeometry.java` | done |
| `src/core/ellipse.rs` — closed ellipse ring, arc-length boundaries, curvature guard, surface | `util/EllipseGeometry.java` | done |
| `src/core/wedge.rs` — voussoir tessellation, tile-splitting UVs, collision triangles | `ArchGeometry.WedgeFace`, `wedgeFaces`, `addFaceQuads`, `wedgeTriangles` | done |
| `src/commands.rs` — the command set's parsing and direction words | `server/BuilderCommand.java` | done |
| `src/lib.rs` — the `Hooks` root | `BuilderToolsMod` | scaffold only |

The region ops, all three curved shapes, the off-grid boxes and the mesher are covered by tests:
`cargo test` runs all 248 of them, including the shape identities (voussoir tiling, ellipse closure, 1 m thickness), the
tessellation invariants (34 quads and 68 triangles per voussoir, tile-split UV phases, face normals
on the expected axis) and the numerical checks in `core::fast`.

`RegionArch::BezierBand` was a placeholder that reported the Bézier gap; the band is now built by
`core::bezier` and the variant carries the real geometry (`Bezier { arch, count, span, center }`), so
wide-wall arching and elliptical rings are complete in the core.

## What is not done yet

`src/lib.rs` registers the gamerule and wires the game-rule listener, the settings panel and the glue
in `hooks.rs`. What is still missing, or limited by API 0.2:

* **Tool commands** — the command set is parsed (`src/commands.rs`) and the tools compute, but no
  command is registered: `register_commands` and `run_mod_command` are not wired, so in game the
  only way to plant a stake is the surveyor item itself.
* **Applying edits** — the port computes change batches and packs them (`core::edit::BlockEdit`,
  equivalence-tested against the per-cell writes), but `edit_blocks` takes host `BlockState`s and
  the core's `BlockId`s are opaque: the table from one to the other belongs to the tool layer that
  picks a block, which is the same gap as the commands above.
* **Preview submission** — wired for what exists: `render_level_stage` submits the local player's
  housing preview as `WorldQuad`/`WorldLine` geometry. The command tools' previews (paths,
  territories, arches) appear once those tools can plant their stakes.
* **Per-player state** — `core::sessions` keys each player's survey, housing tool, settings, golems
  and held claims on their UUID, and `hooks.rs` plants into it from the click's `player`. Claims are
  owned by the stable key `player:<uuid-hex>` rather than the display name, so a rename cannot lose
  one; `claim_area` and `release_claim` enforce ownership.
* **Rotated / off-grid blocks** — geometry, picking and collision boxes are ported in `core::offgrid`,
  with a packed `f32` picking path (`PackedTransform`, ray turned into the block's space once) that
  measures 2.8× faster than the exact path. What the build cannot give is a *collision shape* that
  follows a turned model: a content block's `shapes` are axis-aligned boxes and there is no
  block-collision hook. Drawing a turned block is possible (`block_entity_submit` with
  `native_draw_cube`, or `draw_model_instances` with `OffGridTransform::matrix`) and is not wired.
* **Entity tool** — `EntityToolScreen`, `EntityDragState`, `EntityRotateState`, and the server-side
  `moveEntity`/`duplicateEntity`/`freezeEntity`.
* **Golem entities** — `core::golem` decides everything (who is a minion, what it does each tick,
  where it fetches from) and `core::sessions` ticks every player's crew with the rule's current
  value, but no entity is spawned, walked or made to break a block yet: that needs entity spawning
  (`level_spawn_mob`), a `GolemAction` match in the glue, and the same block-state table as edits.
  The rule itself is wired both ways: it is registered at load, and `game_rule_changed` stores its
  new value in `hooks::fetching_enabled()` whenever `/gamerule GolemFetching` is used.
* **The map HUD** — the claim feed and overlay geometry are ready; drawing a minimap or a sidebar
  list is client UI the port has not built (see the settings screen above).
* **Brushes** — `paint`, `scatter`, `smooth` and their preview sphere.
* **The settings panel's other halves** — the panel itself is built (below), but Java's screen also
  sends `WorldSettingsPacket` and `PlayerAbilitiesPacket` and saves the settings to a config file;
  the port keeps the values in memory and has no packet layer yet.
* **Mesh collision** — the terrain *is* meshed and drawn in game (see below), but collision stays the
  blocks': this API reads a state's collision shape and never lets a mod answer it, so the player
  walks on the block surface under the mesh. Smoothing moves the visible slope, not the floor.
  Sounds and the mod's network packets are not ported at all.
* **Packing the wedge surface into draw calls** (`wedgeQuads`). `wedge.rs` produces the tessellated
  quads with their tile-split UVs and the collision triangles; turning a `TileQuad` plus a
  `FaceKind` into a vertex buffer needs Horizon's block-sprite access, which the port has not wired
  yet. The geometry and UV logic itself is ported and tested.

### Smooth terrain, live from the Creative panel

The world's ground can be drawn as a smooth mesh instead of blocks, and the *Smooth terrain* row at
the top of the Creative panel switches it while playing. Three API 0.2 features carry it:

* **`add_section_geometry`** registers one renderer for the game. The mesher asks it for every
  section it builds, on its workers, so the quads belong to that section's mesh: they are rebuilt
  with it, never duplicated, and dropped when it unloads.
* **`block_render_visibility`** is answered by a block part attached to every replaced state
  (`TerrainPart`, attached per id in `register` — ground *and* water). While the mode is smooth those
  states are hidden from the mesher; light, collision and the server are untouched.
* **`refresh_render_visibility`** remeshes the loaded sections when the answer changes — that is the
  switch, and it is called from the level tick only when the panel's value actually changed.

**Rounded sides.** The surface is an isosurface through a density field that is blurred horizontally
(two passes of a 3×3 box in x and z), so a one- or two-block step is drawn as a ramp about two blocks
wide instead of a wall. The blur is *maximum-preserving*: it may only fill a step, never eat into
solid ground, so a sill or a spire keeps its height and no column loses a block to the smoothing.
Vertically the field is exactly the blocks', which is why a flat surface stays halfway through its
top block.

**Water that meets the shore.** Water is hidden like the ground and meshed as a second, translucent
surface from its own field on the same padded lattice. Both surfaces are generated from the same
corner lattice, and a water corner that has no water of its own climbs to the *ground's* height
there, so the water's edge and the terrain's edge coincide: the shoreline is sealed, and water wraps
around a rock that breaks the surface instead of stopping a block short of it. Buried water (inside a
hill) is not drawn.

**Seams.** A section is sampled two blocks out on every side, and every lattice point inside it is
computed from the same blurred neighbourhood as its neighbour's — so two sections agree on the plane
they share. Quads on the section's far lattice line are clamped onto the boundary rather than
dropped, which is what keeps a 16-block border from showing a crack; a quad lying in the section's
ceiling plane is left to the section above.

Only natural ground is meshed (`TERRAIN_BLOCKS`: stone family, dirt family, sand, gravel, clay, snow,
sandstone, bedrock) plus water. A build stays exactly as built: hiding is per state, and a house's
planks are not in that set. Sampling a section is 9,261 block reads; planning it — sample, blur,
isosurface and water — measures **0.90 ms** per 16³ section in the bench, on a meshing worker thread.

**Collision is still the blocks'.** This API exposes a state's collision shape read-only
(`level_block_shape`, `block_collision`) and has no collision-shape hook, so a mesh cannot be the
player's floor; the two hooks that touch movement (`player_move`, `entity_move`) can veto or rewrite a
delta on the *server* only, which the client does not predict and would rubber-band. What the port can
do, it does: because the surface is only ever raised, never lowered, no mesh stands below a block top
where the player would float, and no invisible mesh collar blocks a step — everything the player
collides with is a real block. Walking up a smoothed slope still steps a whole block at a time, and
the visual surface above the feet is where that shows.

## Tools beyond the Java mod

Two object classes from map editors (Narodnaya Karta's *линейные* and *площадные* objects) have no
Java counterpart and are now in the core, because the mod's only curved tools were an arch and an
ellipse ring:

* **Linear objects** (`core/path.rs`, `/road`, `/path`, `/fence`) — a Catmull–Rom path through the
  drawn points, swept with a width and a thickness. Every band is a separate block slot
  (`PathBands::{surface, filler, edge}`), so `road gravel cobble 5 2` lays a 5-wide gravel road two
  blocks thick over cobble, and `fence oak_fence` lays a one-block line. Sampling is 0.5 blocks, and
  a test flood-fills the result to prove the surface stays 4-connected through a bend.
* **Territorial objects** (`core/polygon.rs`, `/territory`, `/poly`) — an arbitrary polygon with a
  border band, a fill, optional diagonal hatching and a stacking depth. Containment is even-odd (so
  concave outlines work) and the border is a true distance to the nearest edge, so a 2-block border
  is 2 cells thick on every side.

Both are data-driven rather than hard-coded: the "sidebar" of a map editor is the `PathStyle` /
`Territory` value, which a Horizon screen would edit directly once mod screens exist.

### Previews, Housing stakes and Wall Polygons

Every polygonal territory previews the same way (`core/preview.rs`): a semi-transparent yellow area
(`0x66FFD400`) over the ground it would cover and a bright yellow ring (`0xFFFFFF00`, 3 px) on its
outline. The fill is merged per row — one quad per horizontal run of covered cells — so a 10 × 10 plot
costs 10 quads and a 200 × 200 one 200, well inside the client's 4,096-quad budget per view.
`polygon_preview` serves plain territories and claims alike, and `polygon_band` gives the footprint
of a wall in the same style.

**Housing Surveyor Stakes** are ordinary stakes tagged `StakeKind::Housing` (`core/survey.rs`), so
planting, unplanting, the 16-stake cap and the A/B/C labels are shared with the other tools.
`core::wall::Housing` collects them, the right-side window writes the `WallSpec` (height, thickness,
batter, cap block), and Enter erects the wall:

* the wall follows every edge of the outline, so a triangle yields a triangular wall and a concave
  outline its concave shape — no shape is special-cased;
* corners are mitred once: a cell is wall when its centre is inside the polygon *and* within the
  course's width of the nearest edge, so two edges meeting at a vertex cannot write the cell twice;
* each course is `batter` thinner than the one below — the same tapered cross-section the arch and
  wedge tools build — and the wall closes into a ridge once a course would be thinner than
  `MIN_COURSE` instead of ending in a sliver;
* the finished wall is packed into one `BlockEdit` (palette + column runs) for the host's bulk write,
  and the whole thing is previewed first: footprint band, base outline, and the outline raised to
  the wall's top with a post at every vertex, so the sidebar's height reads before Enter.

The wall starts at the lowest stake, so a survey planted on a shelf builds on the shelf. A stake sits
at a block's centre, so a housing outline runs through cell centres; a course's width is measured to
cell centres exactly as `Border::width` is, which makes a 1-block course two cells deep on the side
where the outline passes through them.

### Private territories on every player's map

A claim is private in *build rights*, not in *knowledge* — the way FTB Chunks and Open Parties and
Claims work — so `core/map.rs` gives every player every claim:

* `claim_feed(&store, viewer)` returns all of them with id, owner, name, outline, vertical band,
  area and a `mine` flag. A stranger sees the same list; only the flag differs.
* `claim_overlay(&store, viewer, y)` draws them in one `Preview`: the viewer's own in the bright
  yellow of the territory preview, everyone else's in amber (`0x55FFC000` fill, `0xFFB8860B`
  outline), so ownership reads without a legend.
* `ClaimStore::revision` moves only when a claim is made or released — a refused claim changes
  nothing, so it never looks like news. A client can skip an unchanged list by comparing it.
* The overlay adds claims only while the view's 4,096-quad/line budgets hold, and counts what it
  left out in `ClaimOverlay::hidden` rather than quietly hiding a border.

### Golem minions

`core/plan.rs` turns any finished outline (a wall polygon, a painted territory, a region shell) into
a `BuildPlan` ordered **band by band** — bottom-up and row by row inside a band — so a wall rises
course by course instead of one tower at a time. `BuildPlan::skip_unbuildable` drops the cells the
owner may not build, which is how a minion inherits its owner's rights.

`core/golem.rs` is the minion itself:

* **Only a player's golem builds.** `SpawnOrigin::Player` is a minion; a village's or a structure's
  golem has `SpawnOrigin::World`, refuses every plan and answers `GolemAction::Refused` without
  touching the world.
* **Iron vs copper.** One profile table: iron lays a block every 10 ticks, carries 8 and reaches 16
  blocks; copper is quicker (6 ticks) but carries 4 and reaches 12.
* **It works block by block:** one `Placed` action per cadence, in plan order, with the change
  recorded for undo. A fetching golem also refuses to mine its own not-yet-built outline (the plan
  reserves those positions) or anything inside a claim its owner may not build in.
* **`/gamerule GolemFetching`** is `GolemRules::fetching`, registered from `Hooks::register` as
  `buildertools:golem_fetching` in the `buildertools:golems` category (gamerule ids are lower-case
  resource locations in game; the sidebar and this README keep the readable name). `true` — the
  golem answers `Fetching { target, block }` with the nearest source within reach, the glue walks it
  there and breaks it, then hands the block back with `Golem::give`; with nothing in reach it answers
  `Waiting` and does not conjure blocks. `false` — the block appears in its hands
  (`BlockAppeared`) and is placed on the next tick.

### Surveyor Stakes and Private Territory

A **Surveyor Stake** is one planted block position; every tool view is derived from the planting
order (`core/survey.rs`): two stakes give a span and its bearing, three give the angle at the middle
one with its turn direction, two stacked in one column give the vertical extent, and all of them in
order give either a linear object's control points (`path_points`) or a territory outline
(`polygon`). Nothing is stored twice, so a stake is just a block in the world.

A **Private Territory** (`core/claim.rs`) pairs an outline with an owner and an inclusive vertical
band. With no `startY`/`endY` it takes the whole world height; with them it takes only that band,
which is what lets a basement be claimed under someone else's plot. Overlap is therefore tested in
three dimensions — the bands must intersect *and* the outlines must intersect — and a shared border
counts as touching, not overlapping. `ClaimStore::may_build` is the single permission check a
placement hook needs.

The stake block and item are declared as package content, not in code:
`data/buildertools/horizon/block/surveyor_stake.json` is `{ "template": "minecraft:stone" }`, and a
block gets a block item of its own id by default, so that one file yields both. See
[CONTENT.md](CONTENT.md) for the full guidelines (layout, templates, components, assets, namespaces,
validation) — and note that `cargo horizon-mod build` accepted this declaration with the same checks
the game runs.

### The Creative settings panel

Java builds a 464-line panel onto the creative inventory by subclassing `CreativeModeInventoryScreen`.
The port splits that in two, and neither half needs the subclass:

* `core/settings.rs` holds every value the tools read — wall height, thickness and batter, the
golem's fetching rule and kind, path width and height, border width, territory depth, reach — and
converts each set into the core type that consumes it (`wall_spec`, `golem_rules`, `path_style`,
`territory`). The defaults equal the core constructors', so a tool used without ever opening the panel
behaves exactly as before. Every write goes through the row's range: `set_text` **refuses** a value
outside it rather than clamping a typo (a held ± button clamps, because that is a command and not a
mistake).
* `core/panel.rs` lays the column out on the right edge of whatever rectangle the screen reports, one
row per setting, controls flush right and labels left; a window too short for all ten rows gets the
ones that fit and a count of the rest, instead of drawing over the inventory.
* `ui.rs` is the client half. Horizon has no mod-owned screen that can sit beside a vanilla one, so the
panel is **drawn over the creative inventory** in `screen_render_post` and takes only the input that
lands on it: a press inside the panel is cancelled so the item grid never sees it, keys and typed
characters are taken only while one of the panel's widgets has focus (Escape, the inventory key and
the creative search keep working), and the widgets themselves — focus, Tab, caret, selection — are the
SDK's own `Widgets` group.

The one visible difference from Java: Horizon's widget set has buttons and text fields but no sliders,
so a number is typed into its field (its range shows as the field's suggestion) and a toggle or named
choice is a button that flips or cycles. Both write the same `BuilderSettings`.

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
