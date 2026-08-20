package net.buildertools.server;

import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/**
 * Server-side handlers. The client cancels the equivalent interactions before any packet is sent,
 * so the handlers here are mostly a safety net for clients that (deliberately or not) send the
 * vanilla interaction packets while holding a builder tool.
 */
public final class ServerEvents {
    private ServerEvents() {
    }

    private static boolean isBuilderTool(Item item) {
        return item instanceof SelectionToolItem
                || item instanceof EntityToolItem
                || item instanceof RulerToolItem
                || item instanceof LaserToolItem
                || item instanceof ScatterToolItem
                || item instanceof SmoothToolItem
                || item instanceof PaintToolItem;
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (isBuilderTool(player.getMainHandItem().getItem())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide()) {
                Item item = player.getMainHandItem().getItem();
                if (isBuilderTool(item)) {
                    return InteractionResult.FAIL;
                }
                if (item instanceof BlockItem) {
                    // Off-grid blocks are placed via OffGridBlockPacket, but the vanilla use-item
                    // packet for the same click may still arrive. Cancel the server's own placement
                    // so a cell does not end up with both a grid block and its off-grid display.
                    BlockPos cell = hitResult.getBlockPos().relative(hitResult.getDirection());
                    if (BuilderServerHandler.isRecentOffGridPlacement(player, cell)
                            || BuilderServerHandler.findOffGrid(level, cell) != null
                            || BuilderServerHandler.vanillaPlacementOverlapsOffGrid(level, cell)) {
                        return InteractionResult.FAIL;
                    }
                }
                return InteractionResult.PASS;
            }
            if (isBuilderTool(player.getMainHandItem().getItem())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            // Swallow interactions (e.g. opening a villager trade GUI) while holding any builder tool.
            if (isBuilderTool(player.getMainHandItem().getItem())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (isBuilderTool(player.getMainHandItem().getItem())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
                BuilderCommand.register(dispatcher, buildContext));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Player.tick() resets noPhysics every tick, so keep it true while No Clip is on.
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (BuilderServerHandler.hasNoClip(player)) {
                    player.noPhysics = true;
                }
            }
            // Keeps the day/night cycle frozen while "Pause Time" is enabled in the Creative Settings.
            BuilderServerHandler.tickPausedLevels(server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.getPlayer() instanceof ServerPlayer serverPlayer) {
                UndoStore.remove(serverPlayer);
                ClipboardStore.remove(serverPlayer);
                BuilderServerHandler.removeNoClip(serverPlayer);
                BuilderServerHandler.removeRecentOffGrid(serverPlayer.getUUID());
            }
        });
    }
}
