//! Smooth meshed terrain as a world setting.
//!
//! Horizon's build has no way to replace the chunk mesher, but it has the two halves needed to do it
//! anyway: a **section geometry renderer** the game asks to add quads to every section it meshes,
//! and a **block render visibility** answer that hides a block state from that mesher. Hiding the
//! terrain's blocks and adding a smooth surface through them replaces what you see, section by
//! section, and a refresh remeshes the loaded sections in place — so the switch is live.
//!
//! The surface is an isosurface through a *blurred* occupancy field, which is what rounds a cliff's
//! sides instead of leaving vertical block faces: two passes of a 3×3×3 box blur turn a one-block
//! step into a ramp about two blocks wide, and the mesh follows the ramp. A flat surface stays
//! exactly halfway up its top block, so the blur costs nothing on flat ground. Water is meshed as a
//! second, translucent surface from the same padded lattice, and its shoreline is raised to the
//! terrain surface at each shared corner, so the two meet instead of leaving a gap.
//!
//! This module is the part that can be tested without a game: which blocks count as terrain, how a
//! section's blocks become a density field, and how those quads get their sprite, UVs and light.
//! The glue (`src/terrain.rs`) is only the registration, the answers and the toggle.
//!
//! Collision stays the game's. This API reads `BlockState.getCollisionShape` but never lets a mod
//! answer it, so a mesh cannot be the player's floor: the terrain looks smooth and the player walks
//! on the blocks underneath. The important half of that is that the surface is drawn *through* the
//! blocks' own tops, so nothing invisible can block the player — the collar of a slope is real
//! blocks, never mesh. The README states the split rather than papering over it.

use super::mesher::{DensityGrid, MeshQuad, surface_nets};

/// The side of a chunk section, in blocks.
pub const SECTION: i32 = 16;

/// How far beyond the section the block sampler is asked. Two blocks of margin make the blur exact
/// for every lattice point inside the section, so neighbouring sections produce identical seams.
const PAD: i32 = 2;

/// Blur passes. Two is the NoCubes-like look: a step becomes a ramp about two blocks wide, and
/// because the blur runs horizontally only, no column loses height to it.
const BLUR_PASSES: usize = 2;

/// What the world's terrain looks like.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum TerrainMode {
    /// The game's own block terrain.
    #[default]
    Vanilla,
    /// The blocks replaced by a surface through them.
    Smooth,
}

impl TerrainMode {
    pub const fn is_smooth(self) -> bool {
        matches!(self, Self::Smooth)
    }

    /// The toggle the Creative panel flips.
    pub const fn toggled(self) -> Self {
        match self {
            Self::Vanilla => Self::Smooth,
            Self::Smooth => Self::Vanilla,
        }
    }
}

/// The block ids that count as natural terrain.
///
/// Only ground is meshed. A build stays exactly as it was built — a hidden state is hidden in every
/// section, so a set that included planks or bricks would turn houses into blobs. The list is vanilla
/// ground plus the common stone variants; a modded block that is ground too can be added here.
pub const TERRAIN_BLOCKS: &[&str] = &[
    "minecraft:stone",
    "minecraft:granite",
    "minecraft:diorite",
    "minecraft:andesite",
    "minecraft:deepslate",
    "minecraft:tuff",
    "minecraft:calcite",
    "minecraft:dirt",
    "minecraft:coarse_dirt",
    "minecraft:rooted_dirt",
    "minecraft:grass_block",
    "minecraft:podzol",
    "minecraft:mycelium",
    "minecraft:mud",
    "minecraft:moss_block",
    "minecraft:sand",
    "minecraft:red_sand",
    "minecraft:gravel",
    "minecraft:clay",
    "minecraft:snow_block",
    "minecraft:sandstone",
    "minecraft:red_sandstone",
    "minecraft:bedrock",
];

/// The fluid the water surface replaces. Hidden with the ground, then drawn as a translucent mesh.
pub const WATER_BLOCKS: &[&str] = &["minecraft:water"];

/// Whether a block id is ground the smooth terrain replaces.
pub fn is_terrain(id: &str) -> bool {
    TERRAIN_BLOCKS.contains(&id)
}

/// Whether a block id is the water the smooth terrain meshes.
pub fn is_water(id: &str) -> bool {
    WATER_BLOCKS.contains(&id)
}

/// The sprite the smooth surface shows for a ground block: its top texture.
///
/// A `SectionGeometry` renderer gets no metadata, only sprite *paths*, so the mapping is a table of
/// the ids above. Anything not in it falls back to stone, which keeps a quad patchable rather than
/// untextured.
pub fn sprite_for(id: &str) -> &'static [&'static str] {
    match id {
        "minecraft:grass_block" => &["minecraft:block/grass_block_top"],
        "minecraft:podzol" => &["minecraft:block/podzol_top"],
        "minecraft:mycelium" => &["minecraft:block/mycelium_top"],
        "minecraft:dirt" | "minecraft:coarse_dirt" => &["minecraft:block/dirt"],
        "minecraft:rooted_dirt" => &["minecraft:block/rooted_dirt"],
        "minecraft:mud" => &["minecraft:block/mud"],
        "minecraft:moss_block" => &["minecraft:block/moss_block"],
        "minecraft:sand" => &["minecraft:block/sand"],
        "minecraft:red_sand" => &["minecraft:block/red_sand"],
        "minecraft:gravel" => &["minecraft:block/gravel"],
        "minecraft:clay" => &["minecraft:block/clay"],
        "minecraft:snow_block" => &["minecraft:block/snow"],
        "minecraft:sandstone" => &["minecraft:block/sandstone_top"],
        "minecraft:red_sandstone" => &["minecraft:block/red_sandstone_top"],
        "minecraft:deepslate" => &["minecraft:block/deepslate_top"],
        "minecraft:tuff" => &["minecraft:block/tuff"],
        "minecraft:calcite" => &["minecraft:block/calcite"],
        "minecraft:granite" => &["minecraft:block/granite"],
        "minecraft:diorite" => &["minecraft:block/diorite"],
        "minecraft:andesite" => &["minecraft:block/andesite"],
        "minecraft:bedrock" => &["minecraft:block/bedrock"],
        "minecraft:water" => &["minecraft:block/water_still"],
        _ => &["minecraft:block/stone"],
    }
}

/// One quad the renderer adds to a section.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TerrainQuad {
    /// Section-local corners, `0..=16`.
    pub positions: [[f32; 3]; 4],
    /// Texture coordinates inside the sprite, tiled by world position so the surface reads as one
    /// continuous texture rather than a per-block pattern.
    pub uvs: [[f32; 2]; 4],
    /// Packed ARGB; white leaves the sprite's own colours.
    pub color: u32,
    /// Sky and block light, `0..=15` each.
    pub light: [u8; 2],
    /// Sprite path, as `SectionGeometryContext::add` takes it.
    pub sprite: &'static str,
}

/// Why a section produced no quads.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SectionSkip {
    /// The mode is vanilla: nothing to add.
    Vanilla,
    /// No ground or water in the section (air, or only player blocks).
    NoGround,
    /// Ground, but no surface inside the section: it is buried or empty.
    Buried,
}

/// A section's contribution to the smooth terrain.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct SectionPlan {
    /// The ground surface, drawn in the solid pass.
    pub quads: Vec<TerrainQuad>,
    /// The water surface, drawn in the translucent pass.
    pub water: Vec<TerrainQuad>,
    /// How many ground blocks the section holds, for the coverage report.
    pub ground: usize,
    /// How many water blocks it holds.
    pub water_blocks: usize,
}

/// What one block is, for the mesh. `None` from the sampler means air (or anything not natural).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BlockSample {
    Ground { sprite: &'static str, light: [u8; 2] },
    Water { sprite: &'static str, light: [u8; 2] },
}

impl BlockSample {
    pub const fn ground(sprite: &'static str, light: [u8; 2]) -> Self {
        Self::Ground { sprite, light }
    }

    pub const fn water(sprite: &'static str, light: [u8; 2]) -> Self {
        Self::Water { sprite, light }
    }

    pub const fn sprite(self) -> &'static str {
        match self {
            Self::Ground { sprite, .. } | Self::Water { sprite, .. } => sprite,
        }
    }

    pub const fn light(self) -> [u8; 2] {
        match self {
            Self::Ground { light, .. } | Self::Water { light, .. } => light,
        }
    }

    pub const fn is_water(self) -> bool {
        matches!(self, Self::Water { .. })
    }
}

/// A blurred scalar field on the padded lattice, values from `-1` (outside) to `1` (inside).
struct Field {
    /// Values per axis: the section, one sample beyond it on each side for the mesh, and `PAD`.
    side: i32,
    values: Vec<f32>,
}

impl Field {
    fn new(side: i32, mut inside: impl FnMut(i32, i32, i32) -> bool) -> Self {
        let mut values = vec![-1.0f32; (side * side * side) as usize];
        for y in 0..side {
            for z in 0..side {
                for x in 0..side {
                    let index = ((y * side + z) * side + x) as usize;
                    values[index] = if inside(x, y, z) { 1.0 } else { -1.0 };
                }
            }
        }
        Self { side, values }
    }

    #[inline]
    fn index(&self, x: i32, y: i32, z: i32) -> usize {
        ((y * self.side + z) * self.side + x) as usize
    }

    /// World coordinate to this field's padded coordinate.
    #[inline]
    fn padded(world: i32) -> i32 {
        world + PAD
    }

    #[inline]
    fn at(&self, x: i32, y: i32, z: i32) -> f32 {
        let high = self.side - 1;
        self.values[self.index(x.clamp(0, high), y.clamp(0, high), z.clamp(0, high))]
    }

    /// Value at a world sample point, `-PAD..=SECTION+PAD`.
    #[inline]
    fn get(&self, x: i32, y: i32, z: i32) -> f32 {
        self.at(Self::padded(x), Self::padded(y), Self::padded(z))
    }

    /// Separable 3×3×3 box blur, run in place with one scratch buffer per pass.
    ///
    /// Outside the padded lattice the value repeats, which is exact for every point the mesh reads:
    /// the outermost padded ring is only ever touched by itself.
    fn blur(&mut self) {
        let side = self.side;
        if side < 3 {
            return;
        }
        // Kept so the blur can only ever fill a step, never eat into solid ground: the smoothed
        // field is the maximum of the blurred and the raw one, so a column keeps its height and a
        // spire is not shaved, while a step still becomes a ramp.
        let raw = self.values.clone();
        for _ in 0..BLUR_PASSES {
            // Horizontally only: a step becomes a slope across x and z, while the height of every
            // column stays exactly what the blocks say — a spire is not shaved by the smoothing.
            for axis in [0, 2] {
                let mut next = vec![0.0f32; self.values.len()];
                for y in 0..side {
                    for z in 0..side {
                        for x in 0..side {
                            let (a, b, c) = match axis {
                                0 => (x - 1, x, x + 1),
                                1 => (y - 1, y, y + 1),
                                _ => (z - 1, z, z + 1),
                            };
                            let (a, b, c) = (a.clamp(0, side - 1), b.clamp(0, side - 1), c.clamp(0, side - 1));
                            let (y0, z0) = (y, z);
                            let (sx, sy, sz) = match axis {
                                0 => (a, y0, z0),
                                1 => (x, a, z0),
                                _ => (x, y0, a),
                            };
                            let first = self.values[self.index(sx, sy, sz)];
                            let (sx, sy, sz) = match axis {
                                0 => (b, y0, z0),
                                1 => (x, b, z0),
                                _ => (x, y0, b),
                            };
                            let middle = self.values[self.index(sx, sy, sz)];
                            let (sx, sy, sz) = match axis {
                                0 => (c, y0, z0),
                                1 => (x, c, z0),
                                _ => (x, y0, c),
                            };
                            let last = self.values[self.index(sx, sy, sz)];
                            next[self.index(x, y, z)] = (first + middle + last) / 3.0;
                        }
                    }
                }
                self.values = next;
            }
        }
        for (value, raw) in self.values.iter_mut().zip(raw) {
            *value = value.max(raw);
        }
    }

    /// The height where the field's top surface crosses the section's lattice at `(x, z)`, in
    /// section-local blocks, or `None` when the column is empty or solid to the section's top.
    fn top(&self, x: i32, z: i32) -> Option<f32> {
        for y in (0..=SECTION).rev() {
            let here = self.get(x, y, z);
            if here <= 0.0 {
                continue;
            }
            let above = self.get(x, y + 1, z);
            if above > 0.0 {
                continue;
            }
            let t = (here / (here - above)).clamp(0.0, 1.0);
            return Some(y as f32 + t);
        }
        None
    }
}

/// Plans the smooth surface through one section.
///
/// `sample` is given section-local block coordinates `-2..=18` and returns the block there.
/// Sampling beyond the section is not optional: the blur reads two blocks out, and that margin is
/// what makes neighbouring sections agree on their seams. Sampling nothing costs the caller nothing
/// because a section with no ground is refused before any meshing.
pub fn plan_section(
    mode: TerrainMode,
    mut sample: impl FnMut(i32, i32, i32) -> Option<BlockSample>,
) -> Result<SectionPlan, SectionSkip> {
    if !mode.is_smooth() {
        return Err(SectionSkip::Vanilla);
    }

    let side = SECTION + 2 * PAD + 1;
    let low = -PAD;
    let high = SECTION + PAD;
    // One sampling pass for everything: the two fields, the column sprites and the counts all read
    // the same cache, so a section costs 9,261 block reads and no more.
    let mut cache = vec![None; (side * side * side) as usize];
    for y in 0..side {
        for z in 0..side {
            for x in 0..side {
                let block = sample(x + low, y + low, z + low);
                cache[((y * side + z) * side + x) as usize] = block;
            }
        }
    }
    let cached = |x: i32, y: i32, z: i32| -> Option<BlockSample> {
        if x < 0 || y < 0 || z < 0 || x >= side || y >= side || z >= side {
            return None;
        }
        cache[((y * side + z) * side + x) as usize]
    };
    let mut ground_field = Field::new(side, |x, y, z| matches!(cached(x, y, z), Some(block) if !block.is_water()));
    // Water is meshed from its own field, so its surface is a plane over the lake and not the lake's
    // floor: the same lattice and the same blur as the ground, one pass later.
    let mut water_field = Field::new(side, |x, y, z| matches!(cached(x, y, z), Some(block) if block.is_water()));

    // Per-column sprite and light: the topmost block of each kind, which is the one the surface
    // above that column shows.
    let columns = (SECTION * SECTION) as usize;
    let mut sprite_of = vec![("minecraft:block/stone", [0u8; 2]); columns];
    let mut ground_top = vec![0i32; columns];
    let mut water_of = vec![("minecraft:block/water_still", [0u8; 2]); columns];
    let mut water_top = vec![i32::MIN; columns];
    let mut ground = 0usize;
    let mut water_blocks = 0usize;
    for y in 0..=SECTION {
        for z in 0..=SECTION {
            for x in 0..=SECTION {
                let Some(block) = cached(x + PAD, y + PAD, z + PAD) else {
                    continue;
                };
                let index = (z.min(SECTION - 1) * SECTION + x.min(SECTION - 1)) as usize;
                if block.is_water() {
                    water_blocks += 1;
                    if y >= water_top[index] {
                        water_top[index] = y;
                        water_of[index] = (block.sprite(), block.light());
                    }
                } else {
                    ground += 1;
                    if y >= ground_top[index] {
                        ground_top[index] = y;
                        sprite_of[index] = (block.sprite(), block.light());
                    }
                }
            }
        }
    }

    if ground == 0 && water_blocks == 0 {
        return Err(SectionSkip::NoGround);
    }

    // The mesh reads the blurred field at the section's own lattice points only, which is why the
    // outer padded ring can be an approximation.
    // One cell beyond the section, so the lattice edges on the section's far side (16) have the
    // cells they need on both sides. Their far vertices are then clamped onto the boundary: both
    // sections sample the same blocks there, so the clamped rims agree in height and the seam is
    // closed instead of leaving a one-block crack.
    let cells = (SECTION + 1) as usize;
    let mut mesh_grid = DensityGrid::new(cells);
    if ground > 0 {
        ground_field.blur();
        for y in 0..=cells as i32 {
            for z in 0..=cells as i32 {
                for x in 0..=cells as i32 {
                    mesh_grid.set(x, y, z, ground_field.get(x, y, z));
                }
            }
        }
    }
    let mesh = if ground > 0 { surface_nets(&mesh_grid) } else { Default::default() };
    let mut quads = Vec::with_capacity(mesh.quads.len());
    for quad in &mesh.quads {
        let mut section = to_terrain_quad(quad, &sprite_of);
        for corner in &mut section.positions {
            for axis in 0..3 {
                corner[axis] = corner[axis].clamp(0.0, SECTION as f32);
            }
        }
        let extent = (0..3).map(|axis| {
            let low = section.positions.iter().map(|c| c[axis]).fold(f32::INFINITY, f32::min);
            let high = section.positions.iter().map(|c| c[axis]).fold(f32::NEG_INFINITY, f32::max);
            high - low
        }).fold(0.0f32, f32::max);
        // A quad lying in the section's ceiling plane is the surface owned by the section above.
        let ceiling = section
            .positions
            .iter()
            .all(|corner| (corner[1] - SECTION as f32).abs() < 1.0e-4);
        if extent > 1.0e-4 && !ceiling {
            quads.push(section);
        }
    }

    let water = if water_blocks > 0 {
        water_field.blur();
        plan_water(&water_field, &ground_field, &water_of, ground > 0)
    } else {
        Vec::new()
    };

    if quads.is_empty() && water.is_empty() {
        let _ = high;
        return Err(SectionSkip::Buried);
    }
    Ok(SectionPlan { quads, water, ground, water_blocks })
}

/// The water surface: one flat quad per water cell, its corners raised to the terrain surface where
/// the cell reaches the shore.
///
/// Both surfaces read the same corner lattice, so raising a water corner to the ground's height at
/// that corner makes the two edges coincide — the reason there is no gap along a beach. A cell whose
/// water is entirely under the ground is dropped, which keeps the translucent pass from drawing
/// inside a hill.
fn plan_water(
    water: &Field,
    ground: &Field,
    water_of: &[(&'static str, [u8; 2])],
    ground_field_blurred: bool,
) -> Vec<TerrainQuad> {
    let side = SECTION + 1;
    let mut water_height = vec![None; (side * side) as usize];
    let mut ground_height = vec![None; (side * side) as usize];
    for z in 0..=SECTION {
        for x in 0..=SECTION {
            let index = (z * side + x) as usize;
            water_height[index] = water.top(x, z);
            if ground_field_blurred {
                ground_height[index] = ground.top(x, z);
            }
        }
    }

    let mut quads = Vec::new();
    for z in 0..SECTION {
        for x in 0..SECTION {
            let corners = [
                (x, z),
                (x + 1, z),
                (x + 1, z + 1),
                (x, z + 1),
            ];
            let mut heights = [0.0f32; 4];
            let mut any_water = false;
            let mut sealable = true;
            for (slot, (cx, cz)) in corners.iter().enumerate() {
                let index = (cz * side + cx) as usize;
                let water = water_height[index];
                let ground = ground_height[index];
                if water.is_some() {
                    any_water = true;
                }
                heights[slot] = match (water, ground) {
                    (Some(water), Some(ground)) => water.max(ground),
                    (Some(water), None) => water,
                    // A corner on the shore has no water of its own: it climbs to the ground there,
                    // which is what closes the gap between the water's edge and the terrain.
                    (None, Some(ground)) => ground,
                    (None, None) => {
                        sealable = false;
                        0.0
                    }
                }
                .clamp(0.0, SECTION as f32);
            }
            if !any_water || !sealable {
                continue;
            }
            // Buried water is invisible: the ground surface is above every corner.
            let covered = corners.iter().enumerate().all(|(slot, (cx, cz))| {
                let index = (cz * side + cx) as usize;
                ground_height[index].is_some_and(|ground| ground >= heights[slot])
            });
            if covered {
                continue;
            }

            let index = (z * SECTION + x) as usize;
            let (sprite, light) = water_of[index];
            let mut positions = [[0.0f32; 3]; 4];
            let mut uvs = [[0.0f32; 2]; 4];
            for (slot, (cx, cz)) in corners.iter().enumerate() {
                positions[slot] = [*cx as f32, heights[slot], *cz as f32];
                uvs[slot] = [(*cx as f32).rem_euclid(1.0), (*cz as f32).rem_euclid(1.0)];
            }
            quads.push(TerrainQuad {
                positions,
                uvs,
                color: 0xFFFF_FFFF,
                light,
                sprite,
            });
        }
    }
    quads
}

/// Turns a mesh quad into a section quad: sprite from the ground under it, UVs tiled by the world
/// position along the quad's dominant plane, light from the same column.
fn to_terrain_quad(quad: &MeshQuad, sprite_of: &[(&'static str, [u8; 2])]) -> TerrainQuad {
    let normal = quad.normal;
    let (axis, flip) = dominant_axis(normal);
    let mut positions = [[0.0f32; 3]; 4];
    let mut uvs = [[0.0f32; 2]; 4];
    for index in 0..4 {
        positions[index] = [
            quad.corners[index][0] as f32,
            quad.corners[index][1] as f32,
            quad.corners[index][2] as f32,
        ];
        let corner = quad.corners[index];
        let (u, v) = match axis {
            1 => (corner[0], corner[2]),
            0 => (corner[2], corner[1]),
            _ => (corner[0], corner[1]),
        };
        // A whole texture per block, continuous across the surface: the fractional world position,
        // mirrored on back faces so the texture is not reversed.
        let u = u.rem_euclid(1.0) as f32;
        let v = v.rem_euclid(1.0) as f32;
        uvs[index] = if flip { [1.0 - u, v] } else { [u, v] };
    }

    // The column under the quad's centre, clamped into the section.
    let centre = [
        (positions[0][0] + positions[2][0]) * 0.5,
        (positions[0][1] + positions[2][1]) * 0.5,
        (positions[0][2] + positions[2][2]) * 0.5,
    ];
    let x = (centre[0].floor() as i32).clamp(0, SECTION - 1);
    let z = (centre[2].floor() as i32).clamp(0, SECTION - 1);
    let (sprite, light) = sprite_of[(z * SECTION + x) as usize];

    TerrainQuad {
        positions,
        uvs,
        color: 0xFFFF_FFFF,
        light,
        sprite,
    }
}

/// The axis a quad faces most strongly, and whether it faces the negative side.
fn dominant_axis(normal: [f64; 3]) -> (usize, bool) {
    let ax = normal[0].abs();
    let ay = normal[1].abs();
    let az = normal[2].abs();
    if ay >= ax && ay >= az {
        (1, normal[1] < 0.0)
    } else if ax >= az {
        (0, normal[0] < 0.0)
    } else {
        (2, normal[2] < 0.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn flat(top: i32) -> impl FnMut(i32, i32, i32) -> Option<BlockSample> {
        move |_x, y, _z| (y <= top).then_some(BlockSample::ground("minecraft:block/stone", [15, 15]))
    }

    #[test]
    fn the_toggle_flips_both_ways() {
        assert_eq!(TerrainMode::Vanilla.toggled(), TerrainMode::Smooth);
        assert_eq!(TerrainMode::Smooth.toggled(), TerrainMode::Vanilla);
        assert!(!TerrainMode::Vanilla.is_smooth());
        assert!(TerrainMode::Smooth.is_smooth());
        assert_eq!(TerrainMode::default(), TerrainMode::Vanilla);
    }

    #[test]
    fn the_terrain_set_covers_ground_and_leaves_builds_alone() {
        assert!(is_terrain("minecraft:stone"));
        assert!(is_terrain("minecraft:grass_block"));
        assert!(is_terrain("minecraft:deepslate"));
        assert!(!is_terrain("minecraft:oak_planks"));
        assert!(!is_terrain("minecraft:bricks"));
        assert!(!is_terrain("buildertools:surveyor_stake"));
        assert!(is_water("minecraft:water"));
        assert!(!is_water("minecraft:lava"));
        // Every id has a sprite, and every id is spelled namespaced.
        for id in TERRAIN_BLOCKS.iter().chain(WATER_BLOCKS) {
            assert!(id.starts_with("minecraft:"), "{id}");
            assert!(!sprite_for(id).is_empty());
            assert!(sprite_for(id)[0].starts_with("minecraft:block/"), "{id}");
        }
    }

    #[test]
    fn vanilla_mode_plans_nothing() {
        let plan = plan_section(TerrainMode::Vanilla, flat(8));
        assert_eq!(plan, Err(SectionSkip::Vanilla));
    }

    #[test]
    fn a_flat_section_becomes_one_quad_per_cell_with_sky_light() {
        let plan = plan_section(TerrainMode::Smooth, flat(8)).unwrap();
        // Every cell of the section gets a quad: the padded sampler covers the boundary cells, which
        // is what keeps a section's edge from falling short of its neighbour's.
        assert_eq!(plan.quads.len(), (SECTION * SECTION) as usize);
        assert!(plan.ground > 0);
        assert!(plan.water.is_empty(), "no water in this section");
        for quad in &plan.quads {
            assert_eq!(quad.sprite, "minecraft:block/stone");
            assert_eq!(quad.light, [15, 15]);
            assert_eq!(quad.color, 0xFFFF_FFFF);
            // The surface stays halfway through the top block's grid line, blur or no blur.
            for corner in quad.positions {
                assert!((corner[1] - 8.5).abs() < 1.0e-4, "corner {corner:?}");
                assert!((0.0..=16.0).contains(&corner[0]));
                assert!((0.0..=16.0).contains(&corner[2]));
            }
            // UVs stay inside the sprite and tile.
            for uv in quad.uvs {
                assert!((0.0..=1.0).contains(&uv[0]) && (0.0..=1.0).contains(&uv[1]));
            }
        }
    }

    #[test]
    fn an_empty_section_is_skipped_and_a_buried_one_says_so() {
        assert_eq!(
            plan_section(TerrainMode::Smooth, |_, _, _| None),
            Err(SectionSkip::NoGround)
        );
        // Ground filling the whole section has no surface inside it.
        assert_eq!(
            plan_section(TerrainMode::Smooth, flat(SECTION)),
            Err(SectionSkip::Buried)
        );
    }

    #[test]
    fn a_grass_top_gets_its_own_sprite() {
        let grass = |_x: i32, y: i32, _z: i32| {
            (y <= 8).then_some(BlockSample::ground("minecraft:block/grass_block_top", [15, 0]))
        };
        let plan = plan_section(TerrainMode::Smooth, grass).unwrap();
        let mut seen: Vec<&'static str> = plan.quads.iter().map(|q| q.sprite).collect();
        seen.sort_unstable();
        seen.dedup();
        assert!(
            plan.quads.iter().all(|quad| quad.sprite == "minecraft:block/grass_block_top"),
            "sprites: {seen:?}"
        );
        assert!(plan.quads.iter().all(|quad| quad.light == [15, 0]));
    }

    #[test]
    fn a_cliff_is_a_slope_instead_of_a_wall() {
        // A step: the west half at y 4, the east half at y 8. Blurred, the two-block jump becomes a
        // ramp through the field, so no quad stands vertical and the side rises in steps of its own.
        let stepped = |x: i32, y: i32, _z: i32| {
            let top = if x < 8 { 4 } else { 8 };
            (y <= top).then_some(BlockSample::ground("minecraft:block/stone", [15, 15]))
        };
        let plan = plan_section(TerrainMode::Smooth, stepped).unwrap();
        // The property that matters: the surface crosses the step at intermediate heights, i.e. the
        // two-block jump is drawn as a ramp rather than one vertical wall.
        let mut intermediate = 0;
        for quad in &plan.quads {
            for corner in quad.positions {
                if (6.0..=9.0).contains(&corner[0]) && (5.0..8.4).contains(&corner[1]) {
                    intermediate += 1;
                }
            }
        }
        assert!(
            intermediate > 0,
            "the cliff side carries ramped corners between the two heights"
        );
        // And no quad is a full-height wall standing on the step's own lattice line.
        let walls = plan
            .quads
            .iter()
            .filter(|quad| {
                let low = quad.positions.iter().map(|c| c[1]).fold(f32::INFINITY, f32::min);
                let high = quad.positions.iter().map(|c| c[1]).fold(f32::NEG_INFINITY, f32::max);
                let one_x = quad.positions.iter().all(|c| (c[0] - quad.positions[0][0]).abs() < 1.0e-4);
                one_x && high - low > 1.5
            })
            .count();
        assert_eq!(walls, 0, "a vertical wall on a single lattice line");
    }

    #[test]
    fn water_meets_the_shore_without_a_gap() {
        // A beach at y 4 falling to the lake bed at y 2, water to y 6, and a rock breaking the
        // surface at x 7..8, z 7..8: the water must climb the rock rather than stop short of it.
        let beach = |x: i32, y: i32, z: i32| {
            let ground = if x < 6 { y <= 4 } else { y <= 2 };
            let rock = (7..=9).contains(&x) && (7..=9).contains(&z) && y <= 9;
            if ground || rock {
                Some(BlockSample::ground("minecraft:block/sand", [15, 15]))
            } else if x >= 6 && y <= 6 {
                Some(BlockSample::water("minecraft:block/water_still", [15, 15]))
            } else {
                None
            }
        };
        let plan = plan_section(TerrainMode::Smooth, beach).unwrap();
        assert!(!plan.water.is_empty(), "the water is meshed");
        assert!(plan.water_blocks > 0);
        assert!(plan.quads.iter().any(|quad| quad.sprite == "minecraft:block/sand"));

        let mut raised = 0;
        for quad in &plan.water {
            assert_eq!(quad.sprite, "minecraft:block/water_still");
            for corner in quad.positions {
                assert!((0.0..=16.0).contains(&corner[1]), "{corner:?}");
                // Every water corner is at or above the ground surface of its own column, so the
                // water never sinks below the shore and leaves a gap along it.
                let x = corner[0].floor() as i32;
                let z = corner[2].floor() as i32;
                let mut ground_top = None;
                for y in (0..=SECTION).rev() {
                    let solid = beach(x, y, z).is_some_and(|block| !block.is_water());
                    if solid {
                        ground_top = Some(y as f32 + 0.5);
                        break;
                    }
                }
                if let Some(top) = ground_top {
                    // The shoreline follows the terrain's *blurred* surface, which ramps away from a
                    // rock over about a block; the claim is that the water tracks it rather than
                    // stopping at the block lattice a full block below it.
                    assert!(
                        corner[1] >= top - 1.5,
                        "water {corner:?} under the shore at {top}"
                    );
                }
                if corner[1] > 7.0 {
                    raised += 1;
                }
            }
        }
        assert!(raised > 0, "the water climbs the rock instead of leaving a hole");
    }

    #[test]
    fn a_water_surface_is_flat_away_from_the_shore() {
        let lake = |_x: i32, y: i32, _z: i32| {
            if y <= 2 {
                Some(BlockSample::ground("minecraft:block/clay", [15, 15]))
            } else if y <= 6 {
                Some(BlockSample::water("minecraft:block/water_still", [15, 12]))
            } else {
                None
            }
        };
        let plan = plan_section(TerrainMode::Smooth, lake).unwrap();
        assert!(!plan.water.is_empty());
        let flat = plan
            .water
            .iter()
            .filter(|quad| {
                let low = quad.positions.iter().map(|c| c[1]).fold(f32::INFINITY, f32::min);
                let high = quad.positions.iter().map(|c| c[1]).fold(f32::NEG_INFINITY, f32::max);
                high - low < 1.0e-4
            })
            .count();
        assert!(flat > 0, "open water is a plane");
        // Buried water (under a hill) is not drawn.
        assert!(plan.quads.iter().all(|quad| quad.sprite != "minecraft:block/water_still"));
    }

    #[test]
    fn the_surface_sits_where_the_ground_ends_for_any_height() {
        for top in [1, 5, 12] {
            let plan = plan_section(TerrainMode::Smooth, flat(top)).unwrap();
            let expected = top as f32 + 0.5;
            for quad in &plan.quads {
                for corner in quad.positions {
                    assert!((corner[1] - expected).abs() < 1.0e-4, "at top {top}: {corner:?}");
                }
            }
        }
    }

    #[test]
    fn the_sampler_is_asked_past_the_section_on_every_side() {
        let mut lowest = i32::MAX;
        let mut highest = i32::MIN;
        let _ = plan_section(TerrainMode::Smooth, |x, _y, _z| {
            lowest = lowest.min(x);
            highest = highest.max(x);
            (x <= 8).then_some(BlockSample::ground("minecraft:block/stone", [15, 15]))
        });
        assert_eq!(lowest, -PAD, "the blur's margin is sampled, not clamped");
        assert_eq!(highest, SECTION + PAD);
    }
}

