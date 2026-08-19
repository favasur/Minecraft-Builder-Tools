# Builder Tools

WorldEdit-style builder tools for Minecraft — in-world selection, copy/paste, fill, undo, and
full entity manipulation, plus a WorldEdit-style command set that applies to the selection.

## Versions & loaders

| Module | Minecraft | Loader | Jar |
|---|---|---|---|
| root (`src/`) | 1.21.1 | NeoForge 21.1.x | `build/libs/buildertools-1.0.0.jar` |
| `forge-1211` | 1.21.1 | Forge | `forge-1211/build/libs/buildertools-forge-1.21.1-1.0.0.jar` |
| `fabric-1211` | 1.21.1 | Fabric | `fabric-1211/build/libs/buildertools-fabric-1.21.1-1.0.0.jar` |
| `neoforge-262` | 26.2 | NeoForge | `neoforge-262/build/libs/buildertools-neoforge-26.2-1.0.0.jar` |
| `forge-262` | 26.2 | Forge | `forge-262/build/libs/buildertools-forge-26.2-1.0.0.jar` |
| `fabric-262` | 26.2 | Fabric | `fabric-262/build/libs/buildertools-fabric-26.2-1.0.0.jar` |

Each loader × version pair shares the same gameplay logic (selection, commands, undo, clipboard,
entity tools, brushes, creative settings, renderers) — only the loader-specific event, packet and
registration glue differs.

The 26.2 modules are standalone Gradle projects (their own wrappers), because the Forge/Fabric
26.2 toolchains need newer Gradle versions than the 1.21.1 NeoForge root build.

## Features

### Selection Tool
Found in the *Tools & Utilities* creative tab.

| Action | Control |
|---|---|
| Set corner 1 | Left-click a block |
| Set corner 2 | Right-click a block |
| Resize the region | Left-click a dark blue face plate and drag (grab from up to 64 blocks) |
| Stretch the blocks | Hold **Alt** + drag a face plate (blocks inside scale to fill the new region) |
| Clear selection | Sneak + right-click |
| Copy selection to clipboard | `Y` |
| Paste clipboard | `V` (anchor = block you're looking at) |
| Fill selection with held block | `B` |
| Undo last fill/paste | `U` |

The selection renders in-world: a translucent cyan region volume with a bright
wireframe edge, gold markers on the set corners, and a white outline around the block under your
crosshair so you can see exactly what you're about to mark. The selection **stays put when you
switch items** — it keeps rendering and is ready to use whenever you pick the Selection Tool up
again. A **dark blue plate
sits at the centre of each face** — the plate under your crosshair lights up, and you can grab it
from up to 64 blocks away. A quick click nudges that face one block out; holding and moving the
mouse expands or shrinks the region in real time (the grabbed plate turns gold while you drag).
While you pick the second corner, a **live preview box** grows from corner 1 to the block under
your crosshair, so you see the exact region you're about to mark before you click.
Copy preserves block states **and** block-entity data (chests, signs, spawners, ...).

### Entity Tool
Found in the *Tools & Utilities* creative tab.

| Action | Control |
|---|---|
| Select entity | Right-click it |
| Free-move the entity | Right-click the selected entity and hold + drag the mouse |
| Deselect | Shift + right-click the entity |
| Move to a block | Right-click a block (entity is placed on top of it) |
| Move up/down 1 block | Sneak + scroll wheel |
| Rotate (22.5° steps) | Scroll wheel |
| Delete selected entity | `X` |
| Duplicate selected entity (full NBT, new UUID) | `J` |
| Freeze / unfreeze entity | `G` |

The selected entity is highlighted with a translucent red box, a wireframe edge and a white guide
line down to the ground (and stays highlighted even after you switch items). Free-moving: grab
it, and it tracks the cursor on a horizontal plane — **Lock to Surface**
keeps it grounded and **Grid Snap** snaps it to the configured **Grid Size** (both in the Creative
Settings panel). Right-clicking an entity selects it directly from the raw mouse input, so it
works even when the vanilla interact event isn't reachable. Holding the Entity Tool also blocks
attacking entities and opening their GUIs.

> Note: entity *scaling* isn't included — the vanilla entity scale API only arrived in 1.21.2,
> so it can't be ported cleanly to 1.21.1.

### Creative Settings window
In **creative mode**, pressing `E` opens the **full vanilla creative inventory** (tabs, search
bar, item picker — everything works exactly as vanilla) with a dark-blue settings panel taking
the empty space to its right (~1/4 of the screen width). The inventory stays exactly where
vanilla centers it; nothing is moved or covered.

| Setting | Effect |
|---|---|
The panel is **scrollable** when its content is taller than the window (scroll wheel over the
panel, scrollbar on its edge).

| Setting | Effect |
|---|---|
| Time of Day | Sets the world time (0–24000) |
| Pause Time | Freezes the day/night cycle |
| Clear / Rain / Thunder | Sets the weather |
| Flight Speed | Creative flight speed multiplier |
| No Clip | Fly through blocks (enables flight) |
| Air Placement | Brushes paint in the air too - scroll changes paint distance |
| Tool Reach | Max click distance for the brush tools (5–64) |
| Brush Opacity | Alpha of the in-world brush preview sphere |
| Fullbright | Client-side full brightness |
| Selection Opacity | Alpha of the in-world selection box |
| Panel Opacity | Alpha of the selection drag-handle plates |
| Display Legend | Shows the control-hints overlay in the corner |
| Lock to Surface | Entity free-move keeps the entity grounded |
| Grid Snap + Grid Size | Entity free-move snaps to a grid |

### WorldEdit-style commands
A command set that applies to the region you selected with the Selection Tool (the selection is
synced to the server automatically, so the commands work in single player and multiplayer).
Block arguments use normal syntax, e.g. `/set minecraft:stone`.

| Command | Effect |
|---|---|
| `/set <block>` | Fills the selection with the block |
| `/replace <to>` / `/replace <from> <to>` | Replaces all non-air / matching blocks |
| `/walls <block>` | Sets the four vertical sides |
| `/outline <block>` | Sets the 12 edges (hollow box outline) |
| `/hollow <block>` | Shell of the block with the inside hollowed out |
| `/faces <block>` | Top + bottom faces only |
| `/overlay <block>` | Places the block above every surface column |
| `/center <block>` | Fills the 1–2 block centre of the region |
| `/copy` / `/cut` | Copies (or cuts) the selection to the clipboard |
| `/paste` | Pastes the clipboard at your position |
| `/move <count> [dir]` | Moves the contents by that many blocks |
| `/stack <count> [dir]` | Repeats the region's contents along a direction |
| `/expand [amount] [dir]` / `/contract [amount] [dir]` | Grows/shrinks the region |
| `/shift <amount> [dir]` | Moves the region box without touching blocks |
| `/pos1` / `/pos2` | Sets the corners at your feet |
| `/sel` | Reports the current selection and size |
| `/wand` | Gives you the Selection Tool |
| `/tools` | Gives you all seven builder tools at once |
| `/undo` / `/redo` | Undo / redo the last block operation |

Directions are `up/u`, `down/d`, `north/n`, `south/s`, `west/w`, `east/e` (default: the
direction you're facing). Undo/redo share the same per-player history as the tools (`U`).

### Ruler Tool
Measures distances between two blocks (client-side only, no world changes).

| Action | Control |
|---|---|
| Mark point A | Left-click a block |
| Mark point B | Right-click a block |
| Clear | Sneak + right-click |

The measurement renders in-world: a white straight segment, red/green/blue axis guides, gold
markers on both points, and the distance (plus X/Y/Z components) in the action bar.

### Laser Tool
Projects a red laser beam from your eye to the block you're looking at (up to 128 blocks),
highlights the hit block, and shows the distance in the action bar whenever the target changes.
No clicks needed — just point.

### Paint / Scatter / Smooth Tools (brushes)
All three paint brushes apply to a sphere (radius 4) around the block you click, are
server-validated, and are fully undoable with `U`.

| Tool | Left-click / right-click |
|---|---|
| **Paint** | Fills the sphere with the held block (block-entity data included) |
| **Scatter** | Randomly places the held block (~50%) on solid surfaces in the sphere |
| **Smooth** | Averages terrain heights across a disc, shaving high columns and raising low ones |

Hold a block in your main hand and click the ground to apply a brush.

## Multiplayer & safety

Everything is client-initiated but **server-validated**:

- Selections are capped at **32,768 blocks** and must be fully loaded and within **256 blocks** of the player.
- Entity operations are distance-checked and players can never be targeted.
- Undo restores block states + block entity data; the last **50** fills/pastes per player are kept.
- Clipboards and undo history are per-player and live server-side (not persisted across restarts).

## Requirements

- Java 21 for the 1.21.1 modules; Java 25 for the 26.2 modules
- Minecraft 1.21.1 with NeoForge 21.1.x (tested with 21.1.235), or Forge / Fabric 1.21.1
- Minecraft 26.2 with NeoForge, Forge or Fabric 26.2

## Build & run

```bash
./gradlew build        # root NeoForge 1.21.1 module (jar in build/libs/)
cd neoforge-262 && ../gradlew build   # NeoForge 26.2
cd forge-262 && ./gradlew build       # Forge 26.2 (standalone wrapper)
cd fabric-262 && ./gradlew build      # Fabric 26.2 (standalone wrapper)
./gradlew runClient    # launches the dev client with the mod (root module)
```

The finished jars are listed in the table above — drop the one for your loader/version into
`mods/`.

## Project layout

```
src/main/java/net/buildertools/
  BuilderToolsMod.java        # mod entry point, registration wiring
  registry/ModItems.java      # item registration (creative tab)
  item/                       # the two tool items
  selection/SelectionManager  # client-side selection state
  server/BuilderCommand.java   # top-level WorldEdit-style command set
  server/SelectionStore.java   # per-player server-side selection (synced)
  client/
    ClientEvents.java         # tool interactions, scroll wheel, keybind actions (game bus)
    KeyBindings.java          # key mapping definitions (mod bus)
    SelectionRenderer.java    # in-world box rendering (RenderLevelStageEvent)
    ClientPackets.java        # client -> server packet helper
  network/                    # packet registration (NeoForge payloads)
  server/
    BuilderServerHandler.java # all server-side operations + validation
    UndoStore.java / ClipboardStore.java / BlockChange.java / ServerEvents.java
```

## Notes

- The tools play sounds on every action — corner marking, copy, paste, fill, undo, entity
  select/move/rotate/delete/duplicate, and errors (selection drag/place/scale, paste, rotate,
  eyedropper-select, error, capture, tile-select).
- The tools are bound to whatever key you set; defaults listed above are all unbound in vanilla.
- The gameplay logic is loader-agnostic and identical across all six modules; each port only
  swaps the event, packet and registration glue for its loader (NeoForge / Forge / Fabric) and
  Minecraft version (1.21.1 / 26.2).
