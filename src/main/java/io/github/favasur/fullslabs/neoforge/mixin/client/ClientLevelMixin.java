package io.github.favasur.fullslabs.neoforge.mixin.client;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.util.SlabContext;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value={ClientLevel.class})
public abstract class ClientLevelMixin
implements BlockGetter {
    @Shadow
    @Final
    private Minecraft minecraft;

    @ModifyVariable(method={"addBreakingBlockEffect(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/phys/HitResult;)V"}, at=@At(value="MIXINEXTRAS:EXPRESSION", shift=At.Shift.AFTER))
    @Definition(id="getBlockState", method={"Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"})
    @Expression(value={"? = ?.getBlockState(?)"})
    private BlockState mixedSlabBreakingParticles(BlockState state, @Local(argsOnly=true) BlockPos pos) {
        if (!state.is((Block)SlabRegistry.MIXED_SLAB)) {
            return state;
        }
        Vec3 crosshair = Objects.requireNonNull(this.minecraft.hitResult).getLocation();
        return SlabRegistry.MIXED_SLAB.forwardSideValue((BlockGetter)this, pos, crosshair, SlabContext::mainState);
    }
}

