package net.buildertools.server;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A single block that was changed by a fill/paste, captured before the change so it can be restored
 * by the undo system.
 */
public record BlockChange(BlockPos pos, BlockState state, CompoundTag blockEntityNbt) {
}
