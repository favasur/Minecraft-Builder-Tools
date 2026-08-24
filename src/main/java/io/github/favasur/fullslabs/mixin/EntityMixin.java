package io.github.favasur.fullslabs.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.ducks.EntityDuck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value={Entity.class})
public class EntityMixin
implements EntityDuck {
    @ModifyArg(method={"vibrationAndSoundEffectsFromBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;ZZLnet/minecraft/world/phys/Vec3;)Z"}, at=@At(value="MIXINEXTRAS:EXPRESSION"))
    @Definition(id="walkingStepSound", method={"Lnet/minecraft/world/entity/Entity;walkingStepSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"})
    @Expression(value={"?.walkingStepSound(?, ?)"})
    private BlockState mixedSlabStepSounds(BlockState state, @Local(argsOnly=true) BlockPos pos) {
        return this.fullslabs$tryGetMixedState(state, pos);
    }

    @Override
    public BlockState fullslabs$tryGetMixedState(BlockState state, BlockPos pos) {
        if (!state.is((Block)SlabRegistry.MIXED_SLAB)) {
            return state;
        }
        Entity self = (Entity)(Object)this;
        BlockEntity blockEntity = self.level().getBlockEntity(pos);
        if (!(blockEntity instanceof MixedSlabBlockEntity)) {
            return state;
        }
        MixedSlabBlockEntity mixedEntity = (MixedSlabBlockEntity)blockEntity;
        return mixedEntity.getState(SlabRegistry.MIXED_SLAB.towards(state, self.position(), pos));
    }
}

