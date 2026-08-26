package net.buildertools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Bridges the real Fabric hooks to the canonical NeoForge-style event shims so the copied
 * {@code ClientEvents}/{@code ServerEvents}/{@code SelectionRenderer}/{@code RotatedBlockRenderer}
 * handlers actually run at runtime instead of being dead no-ops.
 *
 * <p>Server/registration hooks are wired from {@link BuilderToolsMod#onInitialize()} (they must
 * exist on both environments); client hooks are wired from {@link BuilderToolsModClient} after the
 * client handlers are registered on {@link NeoForge#EVENT_BUS}.
 */
public final class FabricHooks {
    private static boolean serverWired;
    private static boolean clientWired;

    private FabricHooks() {
    }

    public static synchronized void registerServer() {
        if (serverWired) {
            return;
        }
        serverWired = true;
        IEventBus bus = NeoForge.EVENT_BUS;

        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                bus.fire(new RegisterCommandsEvent(dispatcher, buildContext)));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                bus.fire(new PlayerEvent.PlayerLoggedInEvent(handler.getPlayer())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                bus.fire(new PlayerEvent.PlayerLoggedOutEvent(handler.getPlayer())));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                bus.fire(new PlayerTickEvent.Post(player));
            }
        });

        // Left click on a block. Fires on both logical sides at the start of the destroy flow
        // (Fabric's AttackBlockCallback is hooked into the same place NeoForge patches); FAIL
        // cancels the break client-side (no packet sent) and server-side alike.
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            PlayerInteractEvent.LeftClickBlock event = new PlayerInteractEvent.LeftClickBlock(player, world, pos,
                    new BlockHitResult(Vec3.atCenterOf(pos), direction, pos, false));
            bus.fire(event);
            return event.isCanceled() ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                    player, world, hitResult.getBlockPos(), hitResult);
            bus.fire(event);
            return event.isCanceled() ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            PlayerInteractEvent.EntityInteract event = new PlayerInteractEvent.EntityInteract(player, world, entity);
            bus.fire(event);
            return event.isCanceled() ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            AttackEntityEvent event = new AttackEntityEvent(player);
            bus.fire(event);
            return event.isCanceled() ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        // Creative tab contents. The tab is built on both logical sides; the registrar routes the
        // canonical accept() calls into the real ItemGroupEntries.
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries ->
                BuilderToolsMod.MOD_EVENT_BUS.fire(
                        new BuildCreativeModeTabContentsEvent(CreativeModeTabs.TOOLS_AND_UTILITIES, entries::accept)));
    }

    public static synchronized void registerClient() {
        if (clientWired) {
            return;
        }
        clientWired = true;
        IEventBus bus = NeoForge.EVENT_BUS;

        // Mod-bus events whose listeners are registered in BuilderToolsMod.onInitialize: the
        // keybindings and the off-grid block entity renderer.
        BuilderToolsMod.MOD_EVENT_BUS.fire(new RegisterKeyMappingsEvent(KeyBindingHelper::registerKeyBinding));
        BuilderToolsMod.MOD_EVENT_BUS.fire(new EntityRenderersEvent.RegisterRenderers(
                (type, provider) -> registerEntityRenderer(type, provider)));

        ClientTickEvents.START_CLIENT_TICK.register(client ->
                bus.fire(new ClientTickEvent.Pre()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                bus.fire(new PlayerTickEvent.Post(client.player));
            }
            bus.fire(new ClientTickEvent.Post());
        });

        HudRenderCallback.EVENT.register((graphics, tickCounter) ->
                bus.fire(new RenderGuiEvent.Post(graphics)));

        WorldRenderEvents.AFTER_ENTITIES.register(context ->
                bus.fire(new RenderLevelStageEvent(RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES,
                        context.camera())));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerEntityRenderer(EntityType<?> type, EntityRendererProvider<?> provider) {
        EntityRendererRegistry.register((EntityType) type, (EntityRendererProvider) provider);
    }
}
