package net.buildertools.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the private {@code BlockDisplay.setBlockState} so off-grid blocks can show any state. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccessor {
    @Invoker("setBlockState")
    void buildertools$setBlockState(BlockState state);
}
