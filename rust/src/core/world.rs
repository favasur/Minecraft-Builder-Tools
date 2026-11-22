//! The world abstraction the region operations run against.

use super::pos::BlockPos;

/// A block state id. The port never interprets the value; it only compares, stores and restores it,
/// which is all the Java mod does with `BlockState` for the region commands.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Default)]
pub struct BlockId(pub u32);

impl BlockId {
    pub const fn new(raw: u32) -> Self {
        Self(raw)
    }

    pub const fn raw(self) -> u32 {
        self.0
    }
}

/// The part of a level the region operations need.
pub trait World {
    /// The id `/set m/:air` writes and that `/replace <to>` skips.
    const AIR: BlockId;

    fn get(&self, pos: BlockPos) -> BlockId;
    fn set(&mut self, pos: BlockPos, id: BlockId);
}
