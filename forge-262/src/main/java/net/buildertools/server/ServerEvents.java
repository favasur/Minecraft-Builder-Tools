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
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

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

    /**
     * Wires every server handler onto Forge 65.1.1's typed event buses. Handlers that can
     * suppress the vanilla behavior return boolean (false cancels the event); the rest are
     * plain consumers.
     */
    public static void register() {
        PlayerInteractEvent.LeftClickBlock.BUS.addListener(ServerEvents::onLeftClickBlock);
        PlayerInteractEvent.RightClickBlock.BUS.addListener(ServerEvents::onRightClickBlock);
        PlayerInteractEvent.EntityInteractSpecific.BUS.addListener(ServerEvents::onEntityInteract);
        AttackEntityEvent.BUS.addListener(ServerEvents::onAttackEntity);
        RegisterCommandsEvent.BUS.addListener(BuilderCommand::register);
        TickEvent.PlayerTickEvent.Post.BUS.addListener(ServerEvents::onPlayerTick);
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(ServerEvents::onPlayerLoggedIn);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(ServerEvents::onPlayerLoggedOut);
    }

    public static boolean onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return true;
        }
        if (isBuilderTool(event.getEntity().getMainHandItem().getItem())) {
            return false;
        }
        return true;
    }

    public static boolean onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return true;
        }
        Player player = event.getEntity();
        Item item = player.getMainHandItem().getItem();
        if (isBuilderTool(item)) {
            return false;
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
                return false;
            }
        }
        return true;
    }

    public static boolean onEntityInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide()) {
            return true;
        }
        // Swallow interactions (e.g. opening a villager trade GUI) while holding any builder tool.
        if (isBuilderTool(event.getEntity().getMainHandItem().getItem())) {
            return false;
        }
        return true;
    }

    public static boolean onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (isBuilderTool(player.getMainHandItem().getItem())) {
            return false;
        }
        return true;
    }

    public static void onPlayerTick(TickEvent.PlayerTickEvent.Post event) {
        if (event.player().level().isClientSide()) {
            return;
        }
        // Player.tick() resets noPhysics every tick, so keep it true while No Clip is on.
        if (BuilderServerHandler.hasNoClip(event.player())) {
            event.player().noPhysics = true;
        }
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            // Send the rotation layer so every rotated block renders rotated on this client.
            RotationStore.syncAllTo(serverPlayer);
            // Send the current Smooth Terrain world settings so this client matches the world.
            net.buildertools.network.ModPackets.sendToClient(serverPlayer.connection.getConnection(), new net.buildertools.network.packet.SmoothTerrainTogglePacket(
                    io.github.favasur.smoothterrain.config.SmoothTerrainConfig.Server.collisionsEnabled,
                    SmoothTerrainWorldRules.smoothness()));
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            UndoStore.remove(serverPlayer);
            ClipboardStore.remove(serverPlayer);
            BuilderServerHandler.removeNoClip(serverPlayer);
            BuilderServerHandler.removeRecentOffGrid(serverPlayer.getUUID());
        }
    }
}
