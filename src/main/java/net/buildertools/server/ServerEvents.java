package net.buildertools.server;

import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Server-side handlers. The client cancels the equivalent events before any packet is sent, so the
 * handlers here are mostly a safety net for clients that (deliberately or not) send the vanilla
 * interaction packets while holding a builder tool.
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

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (isBuilderTool(event.getEntity().getMainHandItem().getItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Player player = event.getEntity();
        Item item = player.getMainHandItem().getItem();
        if (isBuilderTool(item)) {
            event.setCanceled(true);
            return;
        }
        if (item instanceof BlockItem) {
            // Off-grid blocks are placed via OffGridBlockPacket, but the vanilla use-item packet for
            // the same click may still arrive. Cancel the server's own placement so a cell does not
            // end up with both a grid block and its off-grid display. Off-grid blocks are real
            // geometry too: a vanilla block may not be placed into a cell its rotated model
            // penetrates (flush-adjacent, touching placements are still allowed).
            BlockPos cell = event.getPos().relative(event.getFace());
            if (BuilderServerHandler.isRecentOffGridPlacement(player, cell)
                    || RotationStore.hasRotation(event.getLevel(), cell)
                    || BuilderServerHandler.findOffGrid(event.getLevel(), cell) != null
                    || BuilderServerHandler.vanillaPlacementOverlapsOffGrid(event.getLevel(), cell)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        // Swallow interactions (e.g. opening a villager trade GUI) while holding any builder tool.
        if (isBuilderTool(event.getEntity().getMainHandItem().getItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (isBuilderTool(player.getMainHandItem().getItem())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BuilderCommand.register(event);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        // Player.tick() resets noPhysics every tick, so keep it true while No Clip is on.
        if (BuilderServerHandler.hasNoClip(event.getEntity())) {
            event.getEntity().noPhysics = true;
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            // Send the rotation layer so every rotated block renders rotated on this client.
            RotationStore.syncAllTo(serverPlayer);
            // Send the current Smooth Terrain world setting so this client matches the world.
            serverPlayer.connection.send(new net.buildertools.network.packet.SmoothTerrainTogglePacket(
                    io.github.favasur.smoothterrain.config.SmoothTerrainConfig.Server.collisionsEnabled));
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            UndoStore.remove(serverPlayer);
            ClipboardStore.remove(serverPlayer);
            BuilderServerHandler.removeNoClip(serverPlayer);
            BuilderServerHandler.removeRecentOffGrid(serverPlayer.getUUID());
        }
    }
}
