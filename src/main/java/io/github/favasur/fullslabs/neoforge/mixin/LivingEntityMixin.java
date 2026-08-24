package io.github.favasur.fullslabs.neoforge.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.favasur.fullslabs.ducks.LivingEntityDuck;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value={LivingEntity.class})
public abstract class LivingEntityMixin
implements LivingEntityDuck {
    @ModifyArg(method={"checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V"}, at=@At(value="MIXINEXTRAS:EXPRESSION"))
    // 1.21.1 builds BlockParticleOption with a 2-arg constructor (particle type + state); the
    // original 1.21.9 mixin matched a 3-arg form that does not exist here.
    @Definition(id="BlockParticleOption", type={BlockParticleOption.class})
    @Expression(value={"new BlockParticleOption(?, ?)"})
    private BlockState mixedSlabLandingParticles(BlockState state, @Local(argsOnly=true) BlockPos landedPosition) {
        return this.fullslabs$getMixedLandingState(state, landedPosition);
    }
}

