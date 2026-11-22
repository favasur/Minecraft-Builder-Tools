//! Measures the mirrored implementations against the alternatives in `core::fast` and against the
//! bulk-edit shape Horizon offers. Run with `cargo run --release --example bench`.
//!
//! These timings compare implementations of this crate on one machine; they are indicative, not a
//! benchmark harness. The numbers quoted in `OPTIMIZATIONS.md` come from this binary.

use buildertools::{BlockBox, BlockChange, BlockId, BlockPos, World, build_ellipse, fill, region_arch};
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

    // 2. Undo memory for one player at the Java mod's limits.
    let per_op = size_of::<BlockChange>() * region.volume() as usize;
    println!(
        "undo at 50 ops x 32,768 blocks: {:.1} MiB per player (BlockChange as recorded)",
        per_op as f64 * 50.0 / (1024.0 * 1024.0)
    );
    println!(
        "  with u16 ids + one relative box origin: {:.1} MiB",
        (16.0 * region.volume() as f64) * 50.0 / (1024.0 * 1024.0)
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

    // 4. Arch plan cost (the per-click derivation).
    let wall = BlockBox::new(BlockPos::new(0, 0, 0), BlockPos::new(7, 0, 0));
    let t = Instant::now();
    for _ in 0..100_000 {
        let _ = region_arch(wall, BlockPos::new(4, 0, 5), buildertools::Face::Up);
    }
    println!("region_arch: {:?}/call", t.elapsed() / 100_000);
}
