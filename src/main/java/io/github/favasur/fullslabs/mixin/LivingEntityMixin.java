package io.github.favasur.fullslabs.mixin;

import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.ducks.LivingEntityDuck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value={LivingEntity.class})
public class LivingEntityMixin
implements LivingEntityDuck {
    @Override
    public BlockState fullslabs$getMixedLandingState(BlockState state, BlockPos landedPosition) {
        if (!state.is((Block)SlabRegistry.MIXED_SLAB)) {
            return state;
        }
        LivingEntity self = (LivingEntity)(Object)this;
        BlockEntity blockEntity = self.level().getBlockEntity(landedPosition);
        if (!(blockEntity instanceof MixedSlabBlockEntity)) {
            return state;
        }
        MixedSlabBlockEntity mixedEntity = (MixedSlabBlockEntity)blockEntity;
        return mixedEntity.getState(SlabRegistry.MIXED_SLAB.towards(state, self.position(), landedPosition));
    }
}

