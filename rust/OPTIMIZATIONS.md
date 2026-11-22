# Could the Rust port do better than the architecture it mirrors?

**Verdict: yes in two places that are now implemented and measured, no in the shapes themselves,
and one "obvious" win (the faster arc length) turns out not to be safely usable for commits.** The
mirrored math is not what is slow; the *shape of the work* is.

Everything below comes from `cargo run --release --example bench` and from tests that fail if a
claim stops holding. Ratios are what matter — absolute times are one machine, one run.

## Status of each claim

| # | Change | Evidence | Risk |
|---|---|---|---|
| 1 | Bulk edit instead of per-block writes | implemented (`core/edit.rs`), 32× fewer writes, equivalence-tested | low — semantics must match `edit_blocks` |
| 2 | Packed undo storage | implemented (`core/history.rs`), **15.6 MiB instead of 31.2** | low — pure encoding |
| 3 | Dense world buffer | measured, **7–8×** on a 32³ fill | low |
| 4 | Composite Gauss + Newton arc length | measured **5× / 2× / 12×** and more accurate | **not safe for commits** — see below |
| 5 | Parallel planning | not measured; safe by construction | medium (threading) |
| 6 | Tessellation reuse | not measured; Java rebuilds per frame | medium |

## 1. Bulk edit — the real architectural win

The Java mod writes a region with one `level.setBlock` per cell. In Java Edition each of those is a
separate world mutation: block-entity removal, neighbour updates, light engine, chunk-section
dirtying, then a chunk resend. Horizon instead takes the whole edit at once as a palette plus
vertical column runs (`ServerHost::edit_blocks`), documented as not notifying neighbours and
resending changed chunks whole.

`core/edit.rs` performs that conversion and `BlockEdit::apply` exists so equivalence is tested, not
asserted:

```
fill 32^3:  1,024 runs for 32,768 cells (32× fewer writes), 20,484 B of runs, pack 2.9 ms
walls 32^3:   124 runs for  3,968 cells (32× fewer writes)
```

Tests: applying a packed edit leaves the world in *exactly* the state the per-cell writes did
(including a 2-block border), for a solid fill, a wall, and a filtered replace; packing is
deterministic and ordered; runs stop merging where the id changes.

**One caveat I will not paper over.** Packing is 2.9 ms of pure CPU for the largest legal operation.
Reducing 32,768 writes to 1,024 runs is proven; that this is *faster* in wall-clock terms depends on
what a per-block write costs in Horizon, which I cannot measure from here. The honest statement is:
the bulk path removes 31,744 world mutations and adds 2.9 ms of local sorting. If a bare write is
≥0.1 µs, the bulk path wins; it needs the real host to confirm.

**A lesson worth recording:** the first packer used a linear scan per change for its column lookup
and palette, and cost **10.3 ms** — slower than the operation it packed (0.4 ms). Hashing the
lookups brought it to 2.4 ms; a single flat sort by `(x, z, y)` measured 2.9 ms and was kept for its
determinism and one allocation less. A "free" constant-factor win is not free until it is measured.

## 2. Packed undo — measured, and it corrects my earlier estimate

Fifty operations of a full 32,768-block selection, per player:

```
raw   31.2 MiB  (20 B/cell as the Java record stores it)
packed 15.6 MiB (2.00×) — u16 offsets from one box origin + u16 palette indices
```

That is a measured 2.00×, not the 25 MiB / ~13 MiB I estimated earlier from byte arithmetic; the
earlier figures assumed fields that the real encoding does not need, and the encoded cell is 10
bytes, not 8. Undo and redo walk the packed cells directly, so restoring an operation allocates
nothing at all — unlike the Java `List<BlockChange>` that is handed back to the world as-is.

Tests: a packed operation restores exactly the previous world (checked over a pre-filled
two-block pattern so `before` is not uniformly air), and the packed form must stay ≥1.9× smaller
than the raw batch or the test fails.

`u16` offsets are exactly sufficient: `MAX_BLOCKS` caps an operation at 32,768 cells, so no offset
can exceed 32,767.

## 3. Dense buffer vs per-position hash — 7–8×

```
fill 32^3: dense 372 µs   hash 2.80 ms   (7–8×)
```

This is a property of the `World` implementation handed to `ops::*`, not of the ported logic, so it
can be adopted without touching a single operation.

## 4. Arc length: faster, more accurate — and not safely usable for commits

The mirrored ellipse integrates its perimeter with an 8,192-sample trapezoid rule and binary-searches
the table for every voussoir boundary. Composite Gauss–Legendre with an advancing-panel Newton solve
is 5×/2×/12× faster (a=8,b=3 / a=20,b=19 / circle) and agrees with a 1,000,000-interval Simpson
reference to <1e-10, where the table is off in the ~1e-7 range. So it is *better mathematics*.

It still must not replace the table for commits, and the benchmark is what shows why:

```
ellipse fidelity over 2,304 (a,b) pairs:
  0 differ in voussoir count
  5 shift a boundary by more than 1e-6 rad (worst 1.20e-6)
```

Five of 2,304 shapes would place their voussoir boundaries at measurably different angles. At a=20 a
1.2e-6 rad shift is 2.4e-5 m — far below a block, so it almost certainly selects the same cells, but
"almost certainly" is exactly what must not be built on when the output is a player's build. A
faster path is only usable here once the region builder exists to diff the *resulting block sets*
against the table's, for every (a,b) pair.

**Recommendation:** keep `build_ellipse` as the commit path; use `core::fast` for previews,
measurement and any future analysis. This is the single clearest case where better math loses to
reproducibility.

## 5. Parallel planning — safe by construction, unmeasured

Planning a batch reads the world and writes only its own output, and boxes decompose into disjoint
columns, so planning can fan out before one serial `edit_blocks` call applies the result. Horizon
native mods run unsandboxed, so std threads are available; a wasm build could not do this, which is
an argument for shipping native. Not measured — there is no region planner in the port yet to
parallelise.

## 6. Tessellation reuse — unmeasured

The Java renderer rebuilds every wedge's quads per frame from world-space math. Wedges of one
arch share every parameter except `theta_start`, so the subdivision angles, face layout and UV
phases can be computed once per arch and reused, leaving a cheap per-wedge transform; collision
triangles can share one BVH per arch instead of one per wedge. The port now produces those quads
(`core/wedge.rs`), so this is the next measurable item once rendering is wired.

## 7. The newer hot paths — measured

Same machine, `cargo run --release --example bench`, one 16³ section whose surface rolls by a block
or so (`cargo test` covers the correctness; these are timings).

| Path | Cost | Notes |
|---|---|---|
| `surface_nets`, 16³ section | **0.49 ms**, 258 quads | 1.9 µs per quad, including the vertex pass and the 12 edge tests per cell |
| Log rim, 12 sides, 13 samples | 14.6 µs with `mesh_height` (scan) → **3.1 µs** with `HeightIndex` | 4.7× |
| Log body, 12 cells | 17.7 µs (scan) → **2.6 µs** (`HeightIndex`) | 6.9× |
| Paint brush, radius 4 | **3.0 µs** per click, 257 cells | walks the sphere in place; allocates only the change batch |
| Voxel walk, 32 blocks | **0.78 µs** per ray, ~43 cells | Amanatides–Woo, no per-cell allocation |
| Off-grid raycast over a slab (exact) | **1.9 µs** per ray | f64 oriented box per candidate cell, nearest hit kept |
| Off-grid picking, packed | **0.67 µs** per ray | 2.8x faster: f32 transform, ray turned once, no per-cell vector |
| Smooth terrain section, 16³ | **0.90 ms**, 331 quads | 9,261 block samples, two horizontal blur passes, the isosurface, and the water surface |

Off-grid picking gained a second, packed path: `PackedTransform` keeps the rotation as nine `f32`s and
turns the *ray* into the block's own space once, then runs an axis-aligned slab test — three dots per
ray instead of nine per box, half the memory traffic, no trigonometry. `for_each_cell_on_ray` walks the
cells through a closure, so the picking path allocates nothing; `cells_on_ray` remains for the debug
overlay. The two paths agree in the tests (forward, inverse and ray distances within 1e-4).

The one structural change this bought is `HeightIndex`: a section's quads filed under a coarse 4-block
XZ grid, so a rim sample tests a handful of quads instead of every quad in the section. It returns the
same heights as the linear scan (checked across 1,089 sample points in `mesher`'s tests), so it is a
pure speed-up, not a change of result. The rest of the new code is already allocation-light: the
mesher preallocates its quad vector, `sphere_into` reuses one buffer, and `OrientedBox::at` computes
the rotation's trig once per box instead of once per axis.

## What must not change

These define what an existing build looks like, or prevent a visible defect. They are the boundary
of "without breaking functionality":

| Constant / rule | Why it must stay |
|---|---|
| `R = H/2 + S²/(8H)`, semicircle clamp on the rise | Defines every existing arch's radius. |
| `MIN_RISE = 0.5`, ribbon cap `min(rise, depth/2 + 1.5)` | Decides when a click arches at all. |
| `MIN_CURVATURE = 0.6` on `b²/a` | Keeps the ellipse's inner edge from pinching into a cusp. |
| `count = max(round(span)+1, round(arcLength))` | Changes how many wedges exist. |
| The 8,192-sample perimeter and the 16-step Bézier polyline | Both feed `count` and the boundary angles, i.e. the block set. |
| The 0.5 mm end-face nudge (sign differs: `+tangent` on the arch, `−tangent` on the other two) | Prevents z-fighting seams between adjacent voussoirs. |
| `/replace <from>` matches the block, not the state | Documented Java semantics. |
| `/outline` = the whole shell | Shipped behaviour, despite the README's "12 edges". |
| `cell_extent`'s 8-corner sampling | It is the exact support function of a box, not a heuristic — there is nothing to improve. |

## What I could not verify

The game still does not run here (no Horizon instance), and edits are not applied yet (`edit_blocks`
needs the block-state table), so:

* the per-block write cost that decides whether finding 1 is a wall-clock win is unmeasured;
* the cell-level block-set equivalence for finding 4 cannot be tested until the region builder
  exists;
* findings 5 and 6 are design arguments, not measurements;
* every number in section 7 is core-only: it measures the port, not a frame in game.

So the analysis covers the finished part of the port. The port is not complete, and no number above
depends on the parts that are missing.
