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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Half-slab breaking: breaking a full double slab removes only the half the player is looking at
 * (the other half stays as a single slab). Vertical states are already single half-blocks, so
 * vanilla breaking applies to them; only horizontal {@code DOUBLE} states are intercepted.
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin implements ServerPlayerInteractionManagerDuck {

    @Shadow
    protected ServerLevel level;
    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", cancellable = true,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private void fullslabs$interceptSlabBreaking(BlockPos pos, CallbackInfoReturnable<Boolean> cir, @Local BlockState state) {
        if (!(state.getBlock() instanceof SlabBlock)
                || state.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE) {
            return;
        }
        HitResult crosshair = Utility.crosshair(this.player);
        if (crosshair.getType() != HitResult.Type.BLOCK) {
            return;
        }
        Utility.StatePair pair = Utility.breakHalf(this.level, state, pos, crosshair);
        if (pair == null) {
            return;
        }
        this.fullslabs$breakSlab(pair, pos);
        cir.setReturnValue(true);
    }

    @Unique
    private void fullslabs$breakSlab(Utility.StatePair pair, BlockPos pos) {
        Block broken = pair.towards().getBlock();
        broken.playerWillDestroy((Level) this.level, pos, pair.towards(), this.player);
        boolean changed = this.level.setBlock(pos, pair.away(), 3);
        if (changed) {
            broken.destroy((LevelAccessor) this.level, pos, pair.towards());
        }
        if (!this.player.isCreative()) {
            ItemStack hand = this.player.getMainHandItem();
            ItemStack handCopy = hand.copy();
            boolean effectiveTool = this.player.hasCorrectToolForDrops(pair.towards());
            hand.mineBlock((Level) this.level, pair.towards(), pos, this.player);
            if (changed && effectiveTool) {
                broken.playerDestroy((Level) this.level, this.player, pos, pair.towards(), null, handCopy);
            }
            if (hand.isEmpty() && !handCopy.isEmpty()) {
                this.fullslabs$onPlayerDestroyItem(this.player, handCopy, InteractionHand.MAIN_HAND);
            }
        }
    }

    @Override
    public void fullslabs$onPlayerDestroyItem(Player player, ItemStack stack, @Nullable InteractionHand hand) {
        // Fire the loader's player-destroy-item hook (NeoForge EventHooks / Forge ForgeEventFactory)
        // reflectively so this shared mixin stays loader-neutral. Vanilla's own item-breaking has
        // already run inside mineBlock; this only notifies mod event listeners.
        try {
            Class<?> hooks = Class.forName("net.neoforged.neoforge.event.EventHooks");
            hooks.getMethod("onPlayerDestroyItem", Player.class, ItemStack.class, InteractionHand.class)
                    .invoke(null, player, stack, hand);
            return;
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // fall through to the Forge factory below
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return;
        }
        try {
            Class<?> hooks = Class.forName("net.minecraftforge.event.ForgeEventFactory");
            hooks.getMethod("onPlayerDestroyItem", Player.class, ItemStack.class, InteractionHand.class)
                    .invoke(null, player, stack, hand);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // No loader hook available; nothing further to do.
        }
    }
}
