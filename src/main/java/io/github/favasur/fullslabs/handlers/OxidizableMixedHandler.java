package io.github.favasur.fullslabs.handlers;

import io.github.favasur.fullslabs.ducks.AxeItemDuck;
import io.github.favasur.fullslabs.handlers.MixedHandler;
import io.github.favasur.fullslabs.util.SlabContext;
import java.util.Optional;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

public class OxidizableMixedHandler
implements MixedHandler {
    public static final OxidizableMixedHandler INSTANCE = new OxidizableMixedHandler();

    private OxidizableMixedHandler() {
    }

    @Override
    public void randomTick(SlabContext context, ServerLevel world, BlockPos pos, RandomSource random) {
        BlockState state = context.mainState();
        Block block = state.getBlock();
        if (!(block instanceof WeatheringCopper)) {
            return;
        }
        WeatheringCopper oxidizable = (WeatheringCopper)block;
        oxidizable.getNextState(state, world, pos, random).ifPresent(s -> context.replaceMain((Level)world, s.getBlock()));
    }

    @Override
    public ItemInteractionResult useItemOn(SlabContext context, ItemStack stack, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        Item item = stack.getItem();
        BlockState state = context.mainState();
        if (item instanceof AxeItemDuck) {
            AxeItemDuck axe = (AxeItemDuck)item;
            Optional<BlockState> stripped = axe.fullslabs$strippedState(world, pos, player, state, new UseOnContext(player, hand, hit));
            if (stripped.isPresent()) {
                boolean success = context.replaceMain(world, stripped.get().getBlock());
                return success ? ItemInteractionResult.SUCCESS : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (item instanceof HoneycombItem) {
            return this.useWaxOnBlock(context, stack, state, world, pos, player);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    private ItemInteractionResult useWaxOnBlock(SlabContext context, ItemStack stack, BlockState state, Level world, BlockPos pos, Player player) {
        return HoneycombItem.getWaxed(state).map(s -> {
            boolean success = context.replaceMain(world, s.getBlock());
            if (!success) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                CriteriaTriggers.ITEM_USED_ON_BLOCK.trigger(serverPlayer, pos, stack);
            }
            stack.consume(1, player);
            world.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(player, state));
            world.levelEvent(player, 3003, pos, 0);
            return ItemInteractionResult.SUCCESS;
        }).orElse(ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION);
    }
}

