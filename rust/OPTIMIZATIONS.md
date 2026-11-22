# Could the Rust port do better than the Java architecture?

Short answer: **yes, in three places that matter, and no in the places that define the mod's
behaviour.** The parts worth changing are where the Java code is shaped by the Java Edition
platform (per-block world writes, a heap-heavy per-player undo, a table-driven arc length). The
parts that must not change are the shape constants — radius formula, rise caps, thresholds — because
those define what an existing build looks like.

Everything below was measured by `cargo run --release --example bench`
(`rust/examples/bench.rs`). The ratios are what matter; the absolute times are one machine and one
run, not a benchmark harness. Every alternative implementation used here lives in
`rust/src/core/fast.rs` and is pinned to the mirrored one by tests before any timing is quoted.

## Measured results

| # | Mirror (as ported from Java) | Alternative | Result |
|---|---|---|---|
| 1 | `World` reached through a per-position hash map | one dense `Vec<BlockId>` over the selection box | **7–8× faster** on a 32³ fill (≈350 µs vs ≈2.4–3.1 ms) |
| 2 | ellipse: 8192-sample trapezoid table + binary search per boundary | composite Gauss–Legendre + advancing-panel Newton | **6× / 2× / 15× faster** (a=8,b=3 / a=20,b=19 / circle), *and* more accurate |
| 3 | `BlockChange` = 20 bytes, one record per block | `u16` ids + one relative box origin (16 bytes), or lane deltas (≈8) | **31.2 MiB → 25 MiB** per player at 50 ops × 32,768 blocks (→ ≈13 MiB with deltas) |
| 4 | one `setBlock` per cell, each with its own neighbour and light update | one bulk edit of column runs | **32,768 calls → 1,024 runs** for a 32³ box (arithmetic, not timed — see below) |
| 5 | per-click arch derivation | unchanged | 178 ns/call, i.e. irrelevant; leave it alone |

### Why the arc-length result is a real improvement and not just a faster wrong answer

The mirrored implementation integrates the perimeter with an 8192-step trapezoid rule. That rule is
the dominant error in the Java mod's ellipse: against a 1,000,000-interval Simpson reference the
mirrored perimeter is off in the ~1e-7 relative range, while the composite Gauss result agrees to
<1e-10. The tests assert exactly that ordering, so the faster path is also the more correct one:

* `perimeter_matches_a_dense_reference_and_the_mirrored_table` — Gauss within 1e-10 of the dense
  reference, and within 1e-6 of the table (the table is the coarse one).
* `newton_boundaries_land_on_equal_arc_length_steps` — every boundary lands on `m/N` of the total
  arc length within 1e-9.
* `gauss_legendre_integrates_a_polynomial_exactly` — the quadrature itself is validated
  independently (16-point Gauss is exact to degree 31).

A 16-point rule over the whole 2π period is *not* enough (the first version was 0.036% off); the
integrand is analytic but the interval is long, so the panel has to be local. That is why the code
uses panels of π/4. This is worth writing down because it is the kind of change that looks free and
is not.

## The structural win: stop writing block by block

This is the largest difference and it is not a constant factor.

The Java mod fills a region with `level.setBlock(...)` in a loop. In Java Edition every one of those
calls is a separate world mutation: block-entity removal, neighbour updates, light-engine work and
chunk-section dirtying, then a chunk resend to clients. For a 32,768-block selection that is 32,768
mutations.

Horizon's mod API takes the whole edit at once — `ServerHost::edit_blocks(dimension, &BlockEdit)`,
where a `BlockEdit` is a palette plus `BlockRun { x, z, y, height, block, only }` column runs, and
the documented contract is that no neighbour is told and the changed chunks go to players whole.
A 32³ selection becomes 1,024 runs instead of 32,768 writes, and the light/neighbour churn
disappears entirely because it never happens per block.

The port is already shaped for this: `ops::*` returns `Vec<BlockChange>` rather than mutating as it
goes, so a "changes → runs" packer is a pure function with no behaviour change. That packer is the
next thing to write, and it is the single highest-value piece of remaining work.

## Where the mirrored design is already right

* **Region ops are pure and testable.** They take a `World` trait, so the same code runs against the
  game, a dense buffer or a hash map in tests. That is what made findings 1 and 4 measurable at all.
* **Geometry is deterministic world-space math.** No baked models, no client state; the server
  commit and the client preview derive from the same function. This is what makes the port
  verifiable by unit tests, and it is the architecture worth keeping.
* **The arch derivation is cheap.** 178 ns per click. Optimising it would be noise.

## Where it is *not* worth changing (fidelity constraints)

These define what an existing build looks like. Changing any of them silently changes saved builds,
so they stay exactly as the Java mod has them:

| Constant / rule | Why it must stay |
|---|---|
| `R = H/2 + S²/(8H)`, semicircle clamp on `H` | Defines every existing arch's radius and curvature. |
| `MIN_RISE = 0.5`, ribbon cap `min(rise, depth/2 + 1.5)` | Decides when a click arches at all, and how far a thin ribbon bulges. |
| `MIN_CURVATURE = 0.6` on `b²/a` | Guards the ellipse's inner edge from pinching into a cusp. |
| The 0.5 mm inward nudge on the shared end face | Fixes z-fighting between adjacent voussoirs. Removing it reintroduces visible seams. |
| `count = max(round(span)+1, round(arcLength))` for arches | Changing it changes how many wedges exist, i.e. the visible band. |
| `/replace <from> <to>` matches the block, not the full state | Documented Java semantics; matching state would change results for stairs/slabs. |
| `/outline` = the whole shell | The shipped behaviour, despite the README saying "12 edges". |

## Recommended order

1. **Changes → `BlockEdit` run packer** (finding 4). Biggest win, no behaviour change, pure function.
2. **Undo as deltas per operation** (finding 3). A 32,768-block op costs 31.2 MiB today; one player
   holding 50 of them is a real server-memory problem, and the fix is local to `history.rs`.
3. **`u16` block ids** (finding 3, same change). Halves every palette, clipboard and undo buffer.
4. **Composite Gauss + Newton for the ellipse** (finding 2). Already implemented and tested in
   `core::fast`; swap `build_ellipse` to it once the shape tests (perimeter, closure, uniform
   widths) are re-run against it — the counts come out identical, so geometry does not move.
5. **Dense block buffer in the Horizon glue** (finding 1). The core is agnostic; this is about the
   `World` implementation the mod hands to `ops::*`.
6. **Tessellation cache** (not measured): the Java renderer rebuilds every wedge's 70-odd quads per
   frame, per wedge, from world-space math. Wedges of one arch share their parameters except
   `theta_start`, so the tessellation (subdivision angles, face layout, UV phases) can be computed
   once per arch and reused, leaving only a cheap per-wedge transform. Same for collision
   triangles: one BVH per arch instead of per wedge.
7. **Parallel planning** (not measured, safe by construction): planning a change batch reads the
   world and writes only its own output, and boxes decompose into disjoint columns, so the planning
   step can fan out over columns before a single serial `edit_blocks` call applies the result.
   Horizon native mods run unsandboxed, so std threads are available; a wasm build could not do
   this, which is a reason to ship native.

## What this analysis does **not** cover

The in-game half of the port is still missing — hooks, the `BlockEdit` application itself, rotated
and off-grid block rendering, the entity tool, brushes, and the creative settings screen. Nothing in
this document assumes those exist; findings 1, 4, 6 and 7 are about the code that *does* exist plus
the API contract read from the SDK. So: the port is not finished, and the analysis above is about
the finished part.

One consequence worth stating plainly: **finding 4 is only available because the target is
Horizon.** On Java Edition the same batching is not exposed to mods, which is why the Java mod is
per-block in the first place. That is the clearest case where the Rust architecture can be better
than the one it mirrors, rather than merely faster.
