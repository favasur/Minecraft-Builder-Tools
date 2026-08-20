package net.buildertools.server;

import net.buildertools.BuilderToolsMod;
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
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;

/**
 * Server-side handlers. The client cancels the equivalent events before any packet is sent, so the
 * handlers here are mostly a safety net for clients that (deliberately or not) send the vanilla
 * interaction packets while holding a builder tool.
 *
 * <p>Forge 26.2 event notes: events are fired on per-class static buses and cancellation is done
 * by returning {@code true} from a {@code @SubscribeEvent} handler on a cancellable event.</p>
 */
// Registered via MinecraftForge.EVENT_BUS.register(ServerEvents.class) in BuilderToolsMod.
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
    public static boolean onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return false;
        }
        if (isBuilderTool(event.getEntity().getMainHandItem().getItem())) {
            return true; // cancel
        }
        return false;
    }

    @SubscribeEvent
    public static boolean onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) {
            return false;
        }
        Player player = event.getEntity();
        Item item = player.getMainHandItem().getItem();
        if (isBuilderTool(item)) {
            return true; // cancel
        }
        if (item instanceof BlockItem) {
            // Off-grid blocks are placed via OffGridBlockPacket, but the vanilla use-item packet for
            // the same click may still arrive. Cancel the server's own placement so a cell does not
            // end up with both a grid block and its off-grid display.
            BlockPos cell = event.getPos().relative(event.getFace());
            if (BuilderServerHandler.isRecentOffGridPlacement(player, cell)
                    || BuilderServerHandler.findOffGrid(event.getLevel(), cell) != null
                    || BuilderServerHandler.vanillaPlacementOverlapsOffGrid(event.getLevel(), cell)) {
                return true; // cancel
            }
        }
        return false;
    }

    @SubscribeEvent
    public static boolean onEntityInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide()) {
            return false;
        }
        // Swallow interactions (e.g. opening a villager trade GUI) while holding any builder tool.
        if (isBuilderTool(event.getEntity().getMainHandItem().getItem())) {
            return true; // cancel
        }
        return false;
    }

    @SubscribeEvent
    public static boolean onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (isBuilderTool(player.getMainHandItem().getItem())) {
            return true; // cancel
        }
        return false;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        BuilderCommand.register(event);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent.Post event) {
        Player player = event.player();
        if (player.level().isClientSide()) {
            return;
        }
        // Player.tick() resets noPhysics every tick, so keep it true while No Clip is on.
        if (BuilderServerHandler.hasNoClip(player)) {
            player.noPhysics = true;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        // Keeps the day/night cycle frozen while "Pause Time" is enabled in the Creative Settings.
        BuilderServerHandler.tickPausedLevels(event.server());
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
