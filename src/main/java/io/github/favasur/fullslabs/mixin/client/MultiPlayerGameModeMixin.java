package io.github.favasur.fullslabs.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.favasur.fullslabs.util.Utility;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={MultiPlayerGameMode.class})
public class MultiPlayerGameModeMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method={"destroyBlock(Lnet/minecraft/core/BlockPos;)Z"}, cancellable=true, at={@At(value="INVOKE", target="Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;")})
    private void interceptSlabBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir, @Local BlockState state, @Local Level world) {
        Utility.StatePair pair = Utility.breakHalf((BlockGetter)world, state, pos, this.minecraft.hitResult);
        if (pair == null) {
            return;
        }
        boolean ret = this.fullslabs$breakSlab(pair, pos, world);
        cir.setReturnValue(ret);
    }

    @Unique
    private boolean fullslabs$breakSlab(Utility.StatePair pair, BlockPos pos, Level world) {
        Block broken = pair.towards().getBlock();
        broken.playerWillDestroy(Objects.requireNonNull(world), pos, pair.towards(), (Player)Objects.requireNonNull(this.minecraft.player));
        boolean changed = world.setBlock(pos, pair.away(), 11);
        if (changed) {
            broken.destroy((LevelAccessor)world, pos, pair.towards());
        }
        return changed;
    }
}

