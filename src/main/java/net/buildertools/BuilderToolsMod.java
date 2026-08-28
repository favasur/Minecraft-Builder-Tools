package net.buildertools;

import net.buildertools.client.ClientEvents;
import net.buildertools.client.KeyBindings;
import net.buildertools.client.OffGridBlockRenderer;
import net.buildertools.client.RotatedBlockRenderer;
import net.buildertools.client.SelectionRenderer;
import net.buildertools.network.ModPackets;
import net.buildertools.registry.ModEntities;
import net.buildertools.registry.ModItems;
import net.buildertools.registry.ModSounds;
import net.buildertools.server.ServerEvents;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(BuilderToolsMod.MODID)
public class BuilderToolsMod {
    public static final String MODID = "buildertools";

    public BuilderToolsMod(IEventBus modEventBus, net.neoforged.fml.ModContainer container) {
        ModEntities.ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);
        modEventBus.addListener(ModPackets::register);
        modEventBus.addListener(this::addCreative);

        // Bundled Smooth Terrain meshing (Surface Nets): config, packets and client hooks.
        io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl.register(container, modEventBus);
        io.github.favasur.smoothterrain.network.SmoothTerrainNetworkNeoForge.register(modEventBus);

        // Register the smoothTerrain world rule (/gamerule smoothTerrain true|false).
        net.buildertools.server.SmoothTerrainWorldRules.init();

        // Server-side safety net: keeps the tools from breaking/placing/interacting even if a
        // misbehaving client sends the vanilla packets anyway. Client cancels these first.
        NeoForge.EVENT_BUS.register(ServerEvents.class);

        if (FMLEnvironment.dist.isClient()) {
            ClientEvents.initializeGeometry();
            modEventBus.addListener(KeyBindings::registerKeyMappings);
            modEventBus.addListener(this::registerRenderers);
            NeoForge.EVENT_BUS.register(ClientEvents.class);
            NeoForge.EVENT_BUS.register(SelectionRenderer.class);
            NeoForge.EVENT_BUS.register(RotatedBlockRenderer.class);
            io.github.favasur.smoothterrain.neoforge.ClientInit.register(modEventBus);
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
