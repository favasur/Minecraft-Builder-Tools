# 🧱 Builder Tools

**WorldEdit-style building, a full entity manipulator, and physics-defying rotated blocks — all in one mod.**

Builder Tools turns Minecraft into a creative studio. Mark a region and fill, replace, copy and
paste it with WorldEdit-style commands. Grab any entity and place, rotate, duplicate or freeze it
by hand. Paint terrain with server-validated brushes. And with the signature **rotated blocks**
feature, place *any* block at *any* angle — then stand vertical slabs flat against the rotated
faces, exactly like you'd expect.

Works on **1.21.1 and 26.2** for **NeoForge, Forge and Fabric**. No dependencies, no config
files — drop the jar in `mods/` and go.

---

## ✨ Feature highlights

| | |
|---|---|
| 🟦 **Region selection** | Live in-world box, drag the face plates to resize, 32k-block cap |
| 🧊 **Rotated blocks** | Place any block at any yaw & pitch — walls, arches, decorations at angles |
| 🟫 **Vertical slabs** | Slabs that stand on their edge — including attached to rotated blocks |
| 🧟 **Entity tool** | Free-move, rotate, duplicate (full NBT), freeze — anything except players |
| 🎨 **Paint brushes** | Paint, Scatter and Smooth tools with an in-world preview sphere |
| ⌨️ **WorldEdit commands** | `/set`, `/replace`, `/copy`, `/paste`, `/undo` and a dozen more |
| 🎛️ **Creative panel** | Time, weather, flight speed, no-clip, fullbright — one panel, no commands |
| 🏔️ **Smooth terrain** | `gamerule smoothTerrain` + `/smoothterrain` for silky movement on slopes |

---

## 🟦 Selection tool — region building, done right

Found in the *Tools & Utilities* creative tab (or grab it with `/wand`).

| Action | Control |
|---|---|
| Set corner 1 / corner 2 | Left-click / right-click a block |
| Resize the region | Grab a dark-blue face plate and drag (up to 64 blocks) |
| Stretch blocks to fill | Hold **Alt** + drag a face plate |
| Copy / Paste clipboard | `Y` / `V` |
| Fill selection with held block | `B` |
| Undo last fill/paste | `U` |

The selection is a translucent cyan volume with a bright wireframe and gold corner markers —
and it **stays visible after you switch items**. A live preview box grows from corner 1 while you
pick corner 2, so you always know exactly what you're about to mark. Copy preserves block states
**and** block-entity data (chests, signs, spawners — everything).

## 🧊 Rotated blocks & vertical slabs — build at any angle

Place any block rotated freely in 3D, then keep building around it:

- Rotate a placed block with **R** (yaw/pitch) — or place against a rotated neighbor's grid.
- **Slabs snap to rotated blocks**: click the center of a rotated face for a **vertical slab
  hugging the face**, the left/right edges for **vertical fins**, and the top/bottom edges for
  **horizontal slabs** laid flush against it.
- Click the inner face of a same-material vertical slab to **merge it into a full double slab** —
  the vanilla rule, now in 3D.
- Every face renders with correct lighting and textures at any angle — no dark or missing faces.

## 🧟 Entity tool — grab the world and move it

| Action | Control |
|---|---|
| Select / deselect entity | Right-click / shift + right-click |
| Free-move | Hold right-click and drag |
| Move to a block | Right-click a block |
| Move up/down 1 block | Sneak + scroll wheel |
| Rotate (22.5° steps) | Scroll wheel |
| Delete / duplicate (full NBT) | `X` / `J` |
| Freeze / unfreeze | `G` |

The selected entity glows with a translucent red box and a guide line to the ground. **Lock to
Surface** keeps it grounded, **Grid Snap** snaps it to a configurable grid — both in the Creative
Settings panel. Players can never be targeted.

## 🎨 Paint, Scatter & Smooth brushes

Three server-validated brushes that paint a sphere (radius 4) around the block you click —
every stroke is fully undoable with `U`:

- **Paint** — fills the sphere with the held block, block-entity data included.
- **Scatter** — randomly sprinkles the held block (~50%) onto solid surfaces.
- **Smooth** — averages terrain heights: shaves hills, raises valleys.

Hold a block in your hand and click the ground. Enable **Air Placement** in settings to paint in
mid-air too (scroll to change paint distance).

## ⌨️ WorldEdit-style commands

Everything applies to your current selection — synced to the server automatically, so the
commands work in single player **and** multiplayer. Block arguments use normal syntax
(`/set minecraft:stone`).

`/set` · `/replace` · `/walls` · `/outline` · `/hollow` · `/faces` · `/overlay` · `/center` ·
`/copy` · `/cut` · `/paste` · `/move` · `/stack` · `/expand` · `/contract` · `/shift` · `/pos1` ·
`/pos2` · `/sel` · `/wand` · `/tools` · `/undo` · `/redo`

Plus `/smoothterrain` and the `smoothTerrain` gamerule for smooth movement collision on slopes.

## 🎛️ Creative settings panel

Press `E` in creative mode: the full vanilla inventory stays exactly where it is, and a
dark-blue settings panel appears to its right. No commands needed for:

**Time & weather** · **Flight speed** · **No Clip** · **Fullbright** · **Tool reach** ·
**Air placement** · **Brush opacity** · **Selection / panel opacity** · **Control legend** ·
**Lock to surface** · **Grid snap**

## 🔒 Multiplayer & safety

Everything is client-initiated but **server-validated** — nothing is trusted from the client:

- Selections are capped at **32,768 blocks**, must be fully loaded and within 256 blocks.
- Entity operations are distance-checked; **players can never be targeted**.
- Undo keeps the last **50** operations per player, block states and block-entity data included.
- Clipboards and undo history live server-side, per player.

## 📦 Downloads

| Loader | Minecraft | Jar |
|---|---|---|
| NeoForge | 1.21.1 | `buildertools-neoforge-1.21.1-0.2.0.jar` |
| Fabric | 1.21.1 | `buildertools-fabric-1.21.1-0.2.0.jar` |
| NeoForge | 26.2 | `buildertools-neoforge-26.2-0.2.0.jar` |
| Fabric | 26.2 | `buildertools-fabric-26.2-0.2.0.jar` |
| Forge | 26.2 | `buildertools-forge-26.2-0.2.0.jar` |

## ✅ Requirements

- **Java 21** for 1.21.1 modules, **Java 25** for 26.2 modules
- Minecraft 1.21.1 with NeoForge 21.1.x (tested with 21.1.235), Forge or Fabric
- Minecraft 26.2 with NeoForge, Forge or Fabric

## 🙏 Credits

Inspired by WorldEdit (region ops) and Hytale (builder brushes & entity handling). Built for
builders, mapmakers and anyone who's ever wanted a ceiling at 37 degrees.
