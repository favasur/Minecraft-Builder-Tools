# Changelog

All notable changes to Builder Tools are documented in this file.

## [0.2.3] — 2026-09-01

### Added

- **Bezier wall arches** — wide walls now bow as a smooth curved band instead of a simple bow.
  The arch follows a quadratic Bezier curve from the wall's root to the click destination, pulled
  through the extended wall itself: the curve leaves the wall parallel to it (no early bend),
  arcs smoothly toward the click, and every depth column of the wall becomes its own curving row
  of voussoirs - multi-block-wide walls keep their whole body as a solid, gap-free curved band.
- **Far-side clicks** — clicking beyond the wall's end (no perpendicular offset) bows the wall
  around its far edge, with the bend easing in along the span so the first blocks stay nearly
  straight.
- **Arch/ring editing** — you can now place blocks flush against a voussoir's curved faces,
  thickening an arch or extending a ring outward block by block.

### Changed

- **Arch mode is now a toggle (ALT+A or ALT+C)** — the mode stays on after you release the
  chord: RMB places the row blocks, LMB-drag stretches the row, moving the mouse bends the live
  green ghost, and LMB commits the arch. Press the chord again to leave.
- The arch/ellipse ghost preview and the committed geometry share one server-side derivation
  (`ArchGeometry.regionArch`), so the curve shown while aiming is exactly what the click
  produces - including the Bezier band, the fan fallback and the circular bow.

## [0.2.2] — 2026-08-31

### Added

- **Arching (ALT+A)** — a brand-new building mechanic that turns a straight row of blocks into a
  smooth, gap-free arch:
  1. Hold **ALT+A** with a block/slab/stair/wall/fence in hand.
  2. Place a row of blocks (RMB) while holding the chord.
  3. Hold **LMB** and drag the mouse to stretch the row, just like ALT-stretching a selection.
  4. Release, then click any block to the side — the row smoothly arches toward it.

  Each block becomes a tapered **voussoir** (a wedge that is wider at the top and narrower at the
  bottom), so the arch tiles with no gaps and stays exactly 1 m thick. Multi-block-wide walls are
  arched into a full curved band — every column of the wall becomes its own row of voussoirs.

- **Ellipse (ALT+E or ALT+C)** — turn a placed rectangle of blocks into a complete, closed
  elliptical ring of voussoirs. The ring is split into equal arc-length steps, so every block is
  a uniform 1 m wide at the centerline no matter how flat or round the ellipse is.

- **Face-relative tunnels** — both mechanics are oriented by the block face you click. Click a
  wall face for a vertical arch/ring, a floor or ceiling face for a horizontal one; the shape is
  generated in the clicked face's plane and extruded along its normal, so you can branch tunnels
  out of any side of a build.

- **Live ghost previews** — while you aim the arch or ellipse click, a bright green preview shows
  the exact curve (with its inner/outer edges) that will be created, computed from the block and
  face under your crosshair. It morphs as you move the mouse, shows the full curved band for
  multi-wide walls, and disappears when the click would fail.

- All of the above is available on every platform: **NeoForge, Forge and Fabric for Minecraft
  1.21.1 and 26.2** (six builds).

### Fixed

- **Undo now clears arches and rings** — undoing an arch/ellipse restores the original wall *and*
  removes the deformed wedges, instead of leaving ghost wedges floating in the layer.
- **Stretch no longer double-applies** on Forge 26.2 (the stretch remap ran twice, duplicating
  blocks and pushing two undo entries).
- Holding the ellipse chord no longer opens the inventory (E) or triggers the vanilla hotbar
  save (C) — those keys are swallowed while ALT is held.

### Changed

- The arch/ellipse geometry is derived from one shared computation on the server and the client
  preview, so what you see while aiming is exactly what the click generates.

## [0.2.0] — 2026-08-28

### Fixed

- **Vertical slabs were invisible.** The vertical-slab model transformed its baked quads with
  0..16-style translations (8/16) inside a pipeline that expects block-local 0..1 units, so every
  vertical slab rendered about eight blocks away from its cell. Translations are now 0.5/1.0 and
  the slabs render exactly where they are placed (this also fixed the long-standing invisible
  texture on vertical slabs).
- **Rotated-block slab placement** — fully rewritten around shared local-frame math
  (`RotatedSlabPlacement` + `RotatedBlockLookup`, used identically by the click path and the
  placement overlay):
  - CENTER of a side face now stands an attached vertical slab flat against the rotated face
    (instead of a right-sided vertical slab or nothing).
  - LEFT/RIGHT edges lay vertical fins along the face tangent; TOP/BOTTOM edges lay horizontal
    slabs flush against the face.
  - Sign-flipped plane offsets on WEST/DOWN/NORTH faces made clicks on those faces resolve to the
    wrong local face and collapse the landing box into the neighbor's own cell — fixed.
  - Refused placements now cancel cleanly and explain themselves in chat instead of silently
    falling through to vanilla.
- **Slab-aware re-rotation**: R-rotating a placed slab keeps its occupied half fixed while only
  the angles change; re-clicking a landing cell that already holds the same slab re-places it in
  place with the clicked region's direction instead of reporting the cell occupied.
- **Merge into double slabs**: clicking the inner face of a same-material rotated vertical slab
  fills it into a full double slab in place, mirroring the vanilla vertical-slab rule.
- **Crash fix**: blocks with empty collision shapes (torches, rails, ...) no longer crash the
  rotated-block lookup and placement paths ("No bounds for empty shape").
- Slope-collision fixes and roughness safety for the smooth-terrain movement path.

### Added

- Smooth-terrain `gamerule` and `/smoothterrain` command, plus `clear-command` upgrades, with
  movement-collision parity across all loaders.

### Changed

- Placement overlays (1.21.1 face-region overlay and 26.2 volume overlay) now mirror the click
  rules exactly — the preview never shows a placement the game would reject, highlights sit on
  the block's actual rotated face, and merge clicks preview the filled block.

## [0.1.4] — 2026-08-24

- Cross-loader port of the rotated-block layer, renderer and selection fixes to all loaders
  (NeoForge/Forge/Fabric on 1.21.1 and 26.2).
- PlaceAnywhere/FreePlacement rotated-block placement; Smooth Terrain crash fixes and rendering.
- Off-grid block collision anchoring and adjacent placement fixes.
- Off-grid block adjacency and pick, hold-to-rotate and `/clear` rework; brush model and item
  renderer mixin fixes.
- Entity Tool `E` interface for manipulating entities.

## [0.1.0] — 2026-08-19

- Initial release of the Hytale-style builder tools for NeoForge, Forge and Fabric on Minecraft
  1.21.1 and 26.2: in-world selection, copy/paste, fill, undo, full entity manipulation and a
  WorldEdit-style command set.

[0.2.3]: https://github.com/favasur/Minecraft-Builder-Tools/releases/tag/0.2.3
[0.2.2]: https://github.com/favasur/Minecraft-Builder-Tools/releases/tag/0.2.2
[0.2.0]: https://github.com/favasur/Minecraft-Builder-Tools/releases/tag/0.2.0
[0.1.4]: https://github.com/favasur/Minecraft-Builder-Tools/releases/tag/0.1.4
