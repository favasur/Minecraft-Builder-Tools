package net.buildertools;

import net.buildertools.client.KeyBindings;
import net.buildertools.client.OffGridBlockRenderer;
import net.buildertools.network.FabricNetwork;
import net.buildertools.network.ModPackets;
import net.buildertools.registry.ModEntities;
import net.buildertools.registry.ModItems;
import net.buildertools.registry.ModSounds;
import net.buildertools.server.ServerEvents;
import net.fabricmc.api.ModInitializer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.FabricEventBus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Fabric entrypoint mirroring the canonical NeoForge mod class: registers the copied
 * registries and wires every canonical handler to the real Fabric hooks via
 * {@link FabricHooks}. The NeoForge-style {@code @Mod} constructor body lives in
 * {@link #onInitialize()} (Fabric instantiates this class with a no-arg constructor).
 */
public final class BuilderToolsMod implements ModInitializer {
    public static final String MODID = "buildertools";

    /** Mod (loader) event bus; fired by the Fabric hook layer. */
    public static final IEventBus MOD_EVENT_BUS = new FabricEventBus();

    @Override
    public void onInitialize() {
        ModEntities.ENTITIES.register(MOD_EVENT_BUS);
        ModItems.ITEMS.register(MOD_EVENT_BUS);
        ModSounds.SOUND_EVENTS.register(MOD_EVENT_BUS);
        MOD_EVENT_BUS.addListener(RegisterPayloadHandlersEvent.class, ModPackets::register);
        MOD_EVENT_BUS.addListener(BuildCreativeModeTabContentsEvent.class, this::addCreative);

        // Bundled Smooth Terrain meshing (Surface Nets): config, packets and client hooks.
        io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl.register(new ModContainer(), MOD_EVENT_BUS);
        FabricNetwork.register();

        // Server-side safety net: keeps the tools from breaking/placing/interacting even if a
        // misbehaving client sends the vanilla packets anyway. Client cancels these first.
        NeoForge.EVENT_BUS.register(ServerEvents.class);
        FabricHooks.registerServer();

        if (FMLEnvironment.dist.isClient()) {
            MOD_EVENT_BUS.addListener(RegisterKeyMappingsEvent.class, KeyBindings::registerKeyMappings);
            MOD_EVENT_BUS.addListener(EntityRenderersEvent.RegisterRenderers.class, this::registerRenderers);
        }
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.OFF_GRID_BLOCK.get(), OffGridBlockRenderer::new);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.SELECTION_TOOL.get());
            event.accept(ModItems.ENTITY_TOOL.get());
            event.accept(ModItems.RULER_TOOL.get());
            event.accept(ModItems.LASER_TOOL.get());
            event.accept(ModItems.SCATTER_TOOL.get());
            event.accept(ModItems.SMOOTH_TOOL.get());
            event.accept(ModItems.PAINT_TOOL.get());
        }
    }
}
