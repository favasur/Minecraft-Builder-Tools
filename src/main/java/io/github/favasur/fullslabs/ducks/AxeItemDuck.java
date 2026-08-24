package io.github.favasur.fullslabs.ducks;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public interface AxeItemDuck {
    public Optional<BlockState> fullslabs$strippedState(Level var1, BlockPos var2, @Nullable Player var3, BlockState var4, UseOnContext var5);
}

