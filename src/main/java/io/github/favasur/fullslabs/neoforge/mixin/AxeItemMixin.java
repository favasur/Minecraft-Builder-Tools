package io.github.favasur.fullslabs.neoforge.mixin;

import io.github.favasur.fullslabs.ducks.AxeItemDuck;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value={AxeItem.class})
public abstract class AxeItemMixin
implements AxeItemDuck {
    @Shadow
    protected abstract Optional<BlockState> evaluateNewBlockState(Level var1, BlockPos var2, Player var3, BlockState var4, UseOnContext var5);

    @Override
    public Optional<BlockState> fullslabs$strippedState(Level world, BlockPos pos, @Nullable Player player, BlockState state, UseOnContext context) {
        return this.evaluateNewBlockState(world, pos, player, state, context);
    }
}

