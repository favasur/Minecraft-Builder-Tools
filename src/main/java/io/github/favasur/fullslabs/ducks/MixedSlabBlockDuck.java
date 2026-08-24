package io.github.favasur.fullslabs.ducks;

import io.github.favasur.fullslabs.handlers.MixedConsumer;
import io.github.favasur.fullslabs.handlers.MixedFunction;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

public interface MixedSlabBlockDuck {
    public <T> T forward(BlockGetter var1, BlockPos var2, MixedFunction<T> var3);

    public <T> T forwardSideValue(BlockGetter var1, BlockPos var2, boolean var3, MixedFunction<T> var4);

    public <T> T forwardSideValue(BlockGetter var1, BlockPos var2, Vec3 var3, MixedFunction<T> var4);

    public void forwardSide(BlockGetter var1, BlockPos var2, boolean var3, MixedConsumer var4);

    public void forwardSide(BlockGetter var1, BlockPos var2, Vec3 var3, MixedConsumer var4);

    public <T, R> R forwardSidesValue(BlockGetter var1, BlockPos var2, MixedFunction<T> var3, BiFunction<T, T, R> var4);

    public void forwardSides(BlockGetter var1, BlockPos var2, MixedConsumer var3);
}

