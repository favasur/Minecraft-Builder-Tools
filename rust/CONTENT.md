# Adding blocks and items to a `.hmod`

Every content mod touches this, so it belongs in the repo rather than in a chat message. Everything
here is from the modding book's *Content mods*, *The manifest and the package* and *Items* chapters
for API 0.2, plus what this mod's own build actually did.

## 1. A package is the root of the tree

```
buildertools.hmod
├── horizon-mod.toml
├── icon.png                 # optional, PNG, square, ≤512×512, ≤256 KiB
├── assets/buildertools/     # client resources: lang, models, blockstates, textures
├── data/buildertools/       # server data: content declarations, loot, recipes, tags
├── Cargo.toml               # code mods
└── src/lib.rs               # code mods
```

There is **no mod-id directory level**. `assets/` and `data/` sit at the package root, and the id
in `horizon-mod.toml` is the namespace (`buildertools`), which is also how the files are addressed.

A code mod (like this one) and a content-only mod use the *same* trees — "there is no separate
native data folder" — so adding content never needs a new pack or a registration hook.

`cargo horizon-mod build` packs `horizon-mod.toml`, `assets/`, `data/`, optional `icon.png` and,
for code, the root Cargo project. Folder packaging skips `.git/` and `target/`.

**Limits:** 65,536 content files, 16 MiB each, 512 MiB total; manifest ≤32 MiB; `horizon-mod.toml`
must declare `capabilities = ["registries"]` or content declarations are refused.

## 2. Declaring a block — one file

```
data/buildertools/horizon/block/surveyor_stake.json
```

```json
{ "template": "minecraft:stone" }
```

`template` names a **vanilla** block; the new block copies it and gets the id `buildertools:surveyor_stake`.
A block **gets a block item of its own id by default**, so one file gives you both the block and the
item — which is exactly what "Surveyor Stakes" needs.

The keys that matter day to day:

| Key | Effect |
|---|---|
| `template` | The vanilla block copied. Unknown templates are refused. |
| `item` | `false` gives the block no item; pick-block takes nothing. |
| `block_item` | (with `item: false`) name another item the block stands for. |
| `pick_item` | A different item pick-block returns, without changing `Block.asItem`. |
| `state_properties` / `default_state` | Extra states. Names may not collide with the template's; the states must fit 2^20. |
| `base_block` | For a farmland-like template: the block it dries or is trampled into. |
| `push_reaction` | `PUSH_PULL`, `PUSH`, `POPPED`, `IMMOVEABLE`, `IGNORE_ENTITY`. |

Example with a state:

```json
{ "template": "minecraft:stone",
  "state_properties": { "lit": { "type": "boolean" } },
  "default_state": { "lit": "false" } }
```

## 3. Declaring an item

```
data/buildertools/horizon/item/<name>.json
```

```json
{ "template": "minecraft:iron_sword",
  "components": { "minecraft:max_damage": 900 } }
```

Only needed for an item that is *not* a block's own item. `components` sets or removes data
components: `minecraft:<type>` sets one, `"!minecraft:<type>": {}` removes the template's. An item
can also place a block with `"block": "buildertools:surveyor_stake"`, or carry an `arm_pose`.

Item-id-specific behaviour does **not** follow a template — a copy of the trident does not throw.

## 4. Assets

```
assets/buildertools/lang/en_us.json
```

```json
{
  "block.buildertools.surveyor_stake": "Surveyor Stake",
  "item.buildertools.surveyor_stake": "Surveyor Stake"
}
```

A block that should look like anything other than its template also needs
`assets/buildertools/blockstates/<name>.json` and `models/block/<name>.json`, and either its own
texture or a `textures` entry pointing at a vanilla one.

```json
{ "variants": { "": { "model": "buildertools:block/surveyor_stake" } } }
```
```json
{ "parent": "minecraft:block/cube_all",
  "textures": { "all": "minecraft:block/stone" } }
```

To make a narrow block connect to vanilla fences, add its id to **both**
`data/minecraft/tags/block/fences.json` and `wooden_fences.json`; for walls, iron bars and panes use
`data/minecraft/tags/block/walls.json`. Give it its own model and shapes for any arms.

### A thin, sign-like block (the Surveyor Stake)

`{"template": "minecraft:oak_sign"}` gives the placement the stakes need: the standing form carries
vanilla's 16-way `rotation` and the wall form its `facing`, so a stake is placed facing the player
exactly as a sign is, and both forms keep the sign's thin collision plate.

Because the post is radially symmetric, one catch-all variant is enough — no 16-entry table:

```json
{ "variants": { "": { "model": "buildertools:block/surveyor_stake" } } }
```

The model is a post with no board (a single 2×16×2 element), and it borrows a vanilla texture, so the
mod ships no PNG. In 26.3 the **item** model lives in `assets/<ns>/items/<name>.json`, not
`models/item/`:

```json
{ "model": { "type": "minecraft:model", "model": "buildertools:block/surveyor_stake" } }
```

Two consequences worth knowing:

* **A sign template brings the sign block entity.** Text, dye, glow ink and wax all come with it.
  With no board in the model there is nowhere for text to be drawn; if the stake must refuse text
  outright that is a code-side matter (`BlockPart`), not a content-file one.
* **Do not override `shapes` down to a bare post.** A vanilla wall sign survives by finding a sturdy
  face, and support/occlusion come from the template rather than from `shapes`. A 2×16×2 post has no
  full face, so wall signs would refuse to attach. Keeping the template's plate is what lets vanilla
  signs attach to a stake "like on walls".

## 5. Namespaces (ports of existing Java mods)

A mod owns its `id` plus anything it lists:

```toml
namespaces = ["kaisyn", "towns_and_towers"]
```

A listed namespace is as good as the mod's own: its `assets/` and `data/` load, and blocks and items
may be declared under `data/<owned_namespace>/horizon/`. `minecraft`, `c` and `horizon` can never be
listed, and two enabled mods may not own the same one (the first in load order wins, the other's
content is left out with a log line).

## 6. Declaring content in code instead

Files are the default. Code declarations exist for content that needs a batch of related ids, and
they use `BlocksEvent` / `ItemsEvent` with `mod_api::Block::new(id, template)` /
`Item::new(id, template)`, then override fields such as `Block::with_item(false)`. They are
load-time declarations, admitted atomically per namespace before resources load — not live registry
entries. `Hooks::register` is for behaviour (block entities, item parts, commands), not for content
that a file can express.

## 7. When you actually need `add_pack_finders`

Only for **extra layered packs** shipped inside the package — an optional texture override, a
compatibility data pack. The mod's own `assets/` and `data/` never need it.

```rust
fn add_pack_finders(&self, _host: HostSide, event: &mut AddPackFindersEvent<'_>) {
    let Ok(location) = Identifier::from_parts("buildertools", "assets/buildertools/resourcepacks/x")
        else { return };
    let mut pack = PackRegistration::new(location, PackType::ClientResources, Text::literal("X"));
    pack.always_active = true;  // required, cannot be deselected
    pack.hidden = true;         // absent from the Resource Packs / Data Packs screens
    let _ = event.packs.add_pack_finders(pack);
}
```

Rules the API enforces: the location is `mod:path` into that package and must contain
`pack.mcmeta` plus a normal `assets/` or `data/` tree; `PackType::ServerData` registrations are
ignored by the resource repository and vice versa, so one hook serves both; failures come back as
`PackFinderError::InvalidPath | MissingPackage | InvalidMetadata | Duplicate | Text` and leave the
repository unchanged. A registration for the other pack type is **not** an error.

## 8. Validate before shipping

```sh
cargo horizon-mod check target/horizon-mod/buildertools.hmod   # the registry's checks
cargo horizon-mod build                                       # packs, and validates content
cargo horizon-mod run                                         # build + launch
```

The game reads content **once at start**, so rebuilding is not enough — restart to see a change.

### This repo, right now

`cargo horizon-mod build` on the content half of this project succeeds and produces
`buildertools.hmod` containing the declared block and lang file, so the declaration itself is
accepted by the same checks the game runs.

Two things to know about *this* checkout:

* The locally installed `cargo-horizon-mod` is **v0.1.0 (a85af177)**, whose manifest schema has no
  `[native]` table, so it refuses the full native manifest (SDK pin `2b77287de…`, the one the book
  and `rust/horizon-mod.toml` target). Content validates fine; packaging the native mod needs a
  newer tool: `cargo install --git https://github.com/horizon-rust/horizon cargo-horizon-mod`.
* Still to add for a complete stake: its own model/texture if it should not look like stone, a
  loot table under `data/buildertools/loot_table/blocks/`, and the interaction code that plants a
  stake on use (the planting/claim logic itself is done in `core::survey` and `core::claim`).
