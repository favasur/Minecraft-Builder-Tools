package io.github.favasur.fullslabs.handlers;

import io.github.favasur.fullslabs.handlers.MixedHandler;
import io.github.favasur.fullslabs.util.SlabContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;

public record RedstoneMixedHandler(int weak, int strong) implements MixedHandler
{
    @Override
    public boolean isSignalSource(SlabContext context) {
        return true;
    }

    @Override
    public int getSignal(SlabContext context, BlockGetter world, BlockPos pos, Direction direction) {
        return this.weak;
    }

    @Override
    public int getDirectSignal(SlabContext context, BlockGetter world, BlockPos pos, Direction direction) {
        return this.strong;
    }
}

