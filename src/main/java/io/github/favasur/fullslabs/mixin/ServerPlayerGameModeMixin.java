package io.github.favasur.fullslabs.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.favasur.fullslabs.ducks.ServerPlayerInteractionManagerDuck;
import io.github.favasur.fullslabs.util.Utility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={ServerPlayerGameMode.class})
public abstract class ServerPlayerGameModeMixin
implements ServerPlayerInteractionManagerDuck {
    @Shadow
    protected ServerLevel level;
    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method={"destroyBlock(Lnet/minecraft/core/BlockPos;)Z"}, cancellable=true, at={@At(value="INVOKE", target="Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;")})
    private void interceptSlabBreaking(BlockPos pos, CallbackInfoReturnable<Boolean> cir, @Local BlockState state) {
        HitResult crosshair = Utility.crosshair((Player)this.player);
        if (crosshair.getType() != HitResult.Type.BLOCK) {
            return;
        }
        Utility.StatePair pair = Utility.breakHalf((BlockGetter)this.level, state, pos, crosshair);
        if (pair == null) {
            return;
        }
        this.fullslabs$breakSlab(pair, pos);
        cir.setReturnValue(true);
    }

    @Unique
    private void fullslabs$breakSlab(Utility.StatePair pair, BlockPos pos) {
        Block broken = pair.towards().getBlock();
        broken.playerWillDestroy((Level)this.level, pos, pair.towards(), (Player)this.player);
        boolean changed = this.level.setBlock(pos, pair.away(), 3);
        if (changed) {
            broken.destroy((LevelAccessor)this.level, pos, pair.towards());
        }
        if (!this.player.isCreative()) {
            ItemStack hand = this.player.getMainHandItem();
            ItemStack handCopy = hand.copy();
            boolean effectiveTool = this.player.hasCorrectToolForDrops(pair.towards());
            hand.mineBlock((Level)this.level, pair.towards(), pos, (Player)this.player);
            if (changed && effectiveTool) {
                broken.playerDestroy((Level)this.level, (Player)this.player, pos, pair.towards(), null, handCopy);
            }
            if (hand.isEmpty() && !handCopy.isEmpty()) {
                this.fullslabs$onPlayerDestroyItem((Player)this.player, handCopy, InteractionHand.MAIN_HAND);
            }
        }
    }
}

