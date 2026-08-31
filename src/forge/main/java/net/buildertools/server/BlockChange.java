package net.buildertools.server;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A single block that was changed by a fill/paste, captured before the change so it can be restored
 * by the undo system. {@code layerCells} lists cells of the mod's block layer (rotated / arch
 * blocks) that the change created there; undo removes those layer entries alongside restoring the
 * vanilla block, so arching (which empties the row's vanilla cells and moves the blocks into the
 * layer) is fully reversible. Empty for ordinary operations.
 */
public record BlockChange(BlockPos pos, BlockState state, CompoundTag blockEntityNbt,
                          List<BlockPos> layerCells) {
    public BlockChange(BlockPos pos, BlockState state, CompoundTag blockEntityNbt) {
        this(pos, state, blockEntityNbt, List.of());
    }
}
