# Changelog

All notable changes to Builder Tools are documented in this file.

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

[0.2.0]: https://github.com/favasur/Minecraft-Builder-Tools/releases/tag/0.2.0
[0.1.4]: https://github.com/favasur/Minecraft-Builder-Tools/releases/tag/0.1.4
