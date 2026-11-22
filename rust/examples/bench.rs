//! Measures the mirrored implementations against the alternatives in `core::fast` and against the
//! bulk-edit shape Horizon offers. Run with `cargo run --release --example bench`.
//!
//! These timings compare implementations of this crate on one machine; they are indicative, not a
//! benchmark harness. The numbers quoted in `OPTIMIZATIONS.md` come from this binary.

use buildertools::{
    BlockBox, BlockChange, BlockEdit, BlockId, BlockPos, DensityGrid, HeightIndex, History, LogSpec,
    BlockSample, MAX_OPS, PackedTransform, TerrainMode, plan_section, raycast_packed,
    OffGridTransform, UnitBox, World, build_ellipse, cells_on_ray, fill, log_cells, log_surface,
    mesh_height, paint_brush, raycast_off_grid, region_arch, surface_nets, walls,
};
use std::collections::HashMap;
use std::mem::size_of;
use std::time::Instant;

#[derive(Default)]
struct DenseWorld {
    cells: Vec<BlockId>,
    min: BlockPos,
    size: [i32; 3],
}

impl DenseWorld {
    fn new(region: BlockBox) -> Self {
        Self {
            cells: vec![BlockId::new(0); region.volume() as usize],
            min: region.min(),
            size: region.size(),
        }
    }

    fn index(&self, pos: BlockPos) -> usize {
        let x = (pos.x - self.min.x) as usize;
        let y = (pos.y - self.min.y) as usize;
        let z = (pos.z - self.min.z) as usize;
        (y * self.size[2] as usize + z) * self.size[0] as usize + x
    }
}

impl World for DenseWorld {
    const AIR: BlockId = BlockId::new(0);
    fn get(&self, pos: BlockPos) -> BlockId {
        self.cells[self.index(pos)]
    }
    fn set(&mut self, pos: BlockPos, id: BlockId) {
        let i = self.index(pos);
        self.cells[i] = id;
    }
}

#[derive(Default)]
struct HashWorld {
    cells: HashMap<BlockPos, BlockId>,
}

impl World for HashWorld {
    const AIR: BlockId = BlockId::new(0);
    fn get(&self, pos: BlockPos) -> BlockId {
        *self.cells.get(&pos).unwrap_or(&Self::AIR)
    }
    fn set(&mut self, pos: BlockPos, id: BlockId) {
        self.cells.insert(pos, id);
    }
}

fn main() {
    let region = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(31, 31, 31));
    let stone = BlockId::new(1);

    println!("region volume: {} blocks", region.volume());
    println!("size_of::<BlockChange>() = {} bytes", size_of::<BlockChange>());

    // 1. Dense vs hash world for a 32^3 fill.
    for _ in 0..2 {
        let mut dense = DenseWorld::new(region);
        let t = Instant::now();
        for _ in 0..20 {
            fill(&mut dense, region, stone);
        }
        let d = t.elapsed() / 20;
        let mut hash = HashWorld::default();
        let t = Instant::now();
        for _ in 0..20 {
            fill(&mut hash, region, stone);
        }
        let h = t.elapsed() / 20;
        println!(
            "fill 32^3: dense {:?}  hash {:?}  (dense is {:.0}x faster)",
            d,
            h,
            h.as_secs_f64() / d.as_secs_f64()
        );
    }

    // 2. Undo memory for one player at the Java mod's limits: raw batch vs the packed history.
    let raw_per_op = size_of::<BlockChange>() * region.volume() as usize;
    let mut history = History::default();
    for _ in 0..MAX_OPS {
        let mut scratch = DenseWorld::new(region);
        history.push(fill(&mut scratch, region, stone));
    }
    let packed = history.byte_size();
    println!(
        "undo at 50 ops x 32,768 blocks: raw {:.1} MiB, packed {:.1} MiB ({:.2}x smaller)",
        raw_per_op as f64 * 50.0 / (1024.0 * 1024.0),
        packed as f64 / (1024.0 * 1024.0),
        (raw_per_op * 50) as f64 / packed as f64
    );

    // 2b. Packing the same batch into one bulk edit (Horizon applies this in a single call).
    let mut scratch = DenseWorld::new(region);
    let changes = fill(&mut scratch, region, stone);
    let t = Instant::now();
    let mut edit = BlockEdit::default();
    for _ in 0..20 {
        edit = BlockEdit::pack(&changes);
    }
    let pack_time = t.elapsed() / 20;
    println!(
        "bulk edit for a 32^3 fill: {} runs for {} cells ({:.0}x fewer writes), {} B, pack {:?}",
        edit.runs.len(),
        edit.cell_count(),
        edit.cell_count() as f64 / edit.runs.len() as f64,
        edit.byte_size(),
        pack_time
    );
    let mut scratch = DenseWorld::new(region);
    let wall_changes = walls(&mut scratch, region, stone);
    let wall_edit = BlockEdit::pack(&wall_changes);
    println!(
        "bulk edit for 32^3 walls: {} runs for {} cells ({:.1}x fewer writes)",
        wall_edit.runs.len(),
        wall_edit.cell_count(),
        wall_edit.cell_count() as f64 / wall_edit.runs.len() as f64
    );

    // 3. Ellipse boundaries: mirrored 8192-sample table vs Gauss/Newton.
    let (nodes, weights) = buildertools::fast::gauss_legendre(16);
    for (a, b) in [(8.0, 3.0), (20.0, 19.0), (4.0, 4.0)] {
        let t = Instant::now();
        for _ in 0..2000 {
            let _ = build_ellipse(
                buildertools::Vec3::splat(0.0),
                buildertools::Vec3::new(1.0, 0.0, 0.0),
                buildertools::Vec3::new(0.0, 0.0, 1.0),
                a,
                b,
            );
        }
        let table = t.elapsed() / 2000;
        let t = Instant::now();
        for _ in 0..2000 {
            let ring = buildertools::fast::perimeter(a, b, &nodes, &weights);
            let count = ((ring.round() as usize).max(6)).max(6);
            let _ = buildertools::fast::thetas(a, b, count, &nodes, &weights);
        }
        let newton = t.elapsed() / 2000;
        println!(
            "ellipse (a={a}, b={b}): table {:?}  gauss/newton {:?}  ({:.0}x faster)",
            table,
            newton,
            table.as_secs_f64() / newton.as_secs_f64()
        );
    }

    // 4. Fidelity gate: would the faster arc length change any commit?
    let mut count_diff = 0;
    let mut shifted = 0;
    let mut worst = 0.0_f64;
    let mut pairs = 0;
    for ai in 1..=48 {
        for bi in 1..=48 {
            let (a, b) = (ai as f64, bi as f64);
            let ring = build_ellipse(
                buildertools::Vec3::splat(0.0),
                buildertools::Vec3::new(1.0, 0.0, 0.0),
                buildertools::Vec3::new(0.0, 0.0, 1.0),
                a,
                b,
            );
            let per = buildertools::fast::perimeter(a, b, &nodes, &weights);
            if (per.round() as usize).max(6) != ring.count {
                count_diff += 1;
            }
            let fast_thetas =
                buildertools::fast::thetas(a, b, ring.count, &nodes, &weights);
            let mut bad = 0.0_f64;
            for (i, t) in ring.thetas.iter().enumerate() {
                let d = (t - fast_thetas[i]).abs();
                worst = worst.max(d);
                bad = bad.max(d);
            }
            if bad > 1.0e-6 {
                shifted += 1;
            }
            pairs += 1;
        }
    }
    println!(
        "ellipse fidelity over {pairs} (a,b) pairs: {count_diff} differ in voussoir count, \
{shifted} shift a boundary by >1e-6 rad (worst {worst:.2e})"
    );

    // 5. Arch plan cost (the per-click derivation).
    let wall = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(7, 0, 0));
    let t = Instant::now();
    for _ in 0..100_000 {
        let _ = region_arch(wall, BlockPos::new(4, 0, 5), buildertools::Face::Up);
    }
    println!("region_arch: {:?}/call", t.elapsed() / 100_000);

    // 6. Smooth terrain: surface nets over one 16^3 section, and how big the mesh is.
    let grid = DensityGrid::from_fn(16, |x, y, z| {
        8.0 - y as f32 + (x as f32 * 0.35).sin() * 0.8 + (z as f32 * 0.2).cos() * 0.5
    });
    let mut quad_count = 0;
    let t = Instant::now();
    for _ in 0..500 {
        quad_count = surface_nets(&grid).quads.len();
    }
    let meshing = t.elapsed() / 500;
    println!(
        "surface_nets 16^3: {meshing:?}/section, {quad_count} quads ({:.1} nanoseconds per quad)",
        meshing.as_secs_f64() * 1.0e9 / quad_count.max(1) as f64
    );
    let mesh = surface_nets(&grid);

    // 7. Rounded logs: a rim sampled against that mesh, per log and per placed cell.
    let spec = LogSpec::DEFAULT;
    let t = Instant::now();
    let mut surface_quads = 0;
    for _ in 0..20_000 {
        surface_quads = log_surface(&spec, [8.5, 4.0, 8.5], 20.0, |x, z| {
            mesh_height(&mesh, x, z, 4.0)
        })
        .quads
        .len();
    }
    println!(
        "log rim on a 16^3 mesh: {:?}/log, {surface_quads} quads",
        t.elapsed() / 20_000
    );

    let t = Instant::now();
    let mut cells = 0;
    for _ in 0..20_000 {
        cells = log_cells(&spec, [8.5, 4.0, 8.5], 20.0, |x, z| mesh_height(&mesh, x, z, 4.0)).len();
    }
    println!("log body: {:?}/log, {cells} cells", t.elapsed() / 20_000);

    // 7b. The same rim through the coarse XZ index instead of a scan of every quad.
    let index = HeightIndex::new(&mesh, 4.0);
    let t = Instant::now();
    for _ in 0..20_000 {
        let _ = log_surface(&spec, [8.5, 4.0, 8.5], 20.0, |x, z| index.height(x, z, 4.0));
    }
    println!(
        "log rim via height index: {:?}/log",
        t.elapsed() / 20_000
    );

    let t = Instant::now();
    for _ in 0..20_000 {
        let _ = log_cells(&spec, [8.5, 4.0, 8.5], 20.0, |x, z| index.height(x, z, 4.0));
    }
    println!(
        "log body via height index: {:?}/log",
        t.elapsed() / 20_000
    );

    // 8. Brushes: a click's sphere of changes, on a dense world.
    let brush_region = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(31, 31, 31));
    let mut world = DenseWorld::new(brush_region);
    let centre = BlockPos::new(16, 16, 16);
    let t = Instant::now();
    let mut painted = 0;
    for click in 0..2_000 {
        painted = paint_brush(
            &mut world,
            centre,
            BlockId::new(if click % 2 == 0 { 1 } else { 2 }),
        )
        .len();
    }
    println!(
        "paint brush: {:?}/click, {painted} blocks",
        t.elapsed() / 2_000
    );

    // 9. Off-grid picking: the voxel walk and the raycast through a slab of turned blocks.
    let t = Instant::now();
    let mut hits = 0;
    for _ in 0..20_000 {
        hits += cells_on_ray([0.5, 64.5, 0.5], [1.0, -0.05, 0.3], 32.0).len();
    }
    println!("cells_on_ray over 32 blocks: {:?}/ray, {hits} cells", t.elapsed() / 20_000);

    let turned = OffGridTransform::new([0.0, 0.1, 0.0], 30.0, 15.0);
    let t = Instant::now();
    let mut found = 0;
    for _ in 0..5_000 {
        if raycast_off_grid([0.5, 64.5, 0.5], [1.0, -0.05, 0.3], 32.0, |cell| {
            (cell.y == 64).then_some((UnitBox::FULL, turned))
        })
        .is_some()
        {
            found += 1;
        }
    }
    println!(
        "off-grid raycast over a slab: {:?}/ray, {found} of 5,000 hit",
        t.elapsed() / 5_000
    );

    // 10. The packed picking path: f32 transform, ray turned once, no per-cell allocation.
    let packed = PackedTransform::from_transform(&turned);
    let half = [0.5f32, 0.5, 0.5];
    let t = Instant::now();
    let mut packed_hits = 0;
    for _ in 0..5_000 {
        if raycast_packed([0.5, 64.5, 0.5], [1.0, -0.05, 0.3], 32.0, |cell| {
            (cell.y == 64).then(|| {
                (
                    packed,
                    [cell.x as f32 + 0.5, 64.5, cell.z as f32 + 0.5],
                    half,
                )
            })
        })
        .is_some()
        {
            packed_hits += 1;
        }
    }
    println!(
        "packed off-grid picking: {:?}/ray, {packed_hits} of 5,000 hit",
        t.elapsed() / 5_000
    );

    // 11. Terrain: planning one section's smooth mesh from a block sampler.
    let t = Instant::now();
    let mut quads = 0;
    for _ in 0..200 {
        let plan = plan_section(TerrainMode::Smooth, |x, y, z| {
            let height = 8.0 + (x as f32 * 0.35).sin() * 1.5 + (z as f32 * 0.2).cos();
            ((y as f32) < height).then_some(BlockSample::ground("minecraft:block/stone", [15, 15]))
        });
        quads = plan.map(|plan| plan.quads.len()).unwrap_or(0);
    }
    println!(
        "terrain section plan 16^3: {:?}/section, {quads} quads",
        t.elapsed() / 200
    );
}
