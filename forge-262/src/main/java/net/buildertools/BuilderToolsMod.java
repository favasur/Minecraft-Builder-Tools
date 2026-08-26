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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 65.1.1 entrypoint. The loader constructs the mod with the mod-loading context, which
 * exposes the mod's event-bus {@link BusGroup} (typed event buses replace the old single
 * {@code IEventBus}); game-lifecycle events are wired through {@code MinecraftForge.EVENT_BUS}
 * and the per-event static {@code BUS} fields.
 */
@Mod(BuilderToolsMod.MODID)
public class BuilderToolsMod {
    public static final String MODID = "buildertools";

    public BuilderToolsMod(FMLJavaModLoadingContext context) {
        BusGroup modBusGroup = context.getModBusGroup();

        ModEntities.ENTITIES.register(modBusGroup);
        ModItems.ITEMS.register(modBusGroup);
        ModSounds.SOUND_EVENTS.register(modBusGroup);
        BuildCreativeModeTabContentsEvent.BUS.addListener(this::addCreative);

        // Bundled Smooth Terrain meshing (Surface Nets): config, packets and client hooks.
        io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl.register();
        io.github.favasur.smoothterrain.network.SmoothTerrainNetworkNeoForge.register();

        // Server-side safety net: keeps the tools from breaking/placing/interacting even if a
        // misbehaving client sends the vanilla packets anyway. Client cancels these first.
        ServerEvents.register();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientEvents.initializeGeometry();
            net.minecraftforge.client.event.RegisterKeyMappingsEvent.BUS.addListener(KeyBindings::registerKeyMappings);
            EntityRenderersEvent.RegisterRenderers.BUS.addListener(this::registerRenderers);
            ClientEvents.register();
            SelectionRenderer.register();
            RotatedBlockRenderer.register();
            io.github.favasur.smoothterrain.neoforge.ClientInit.register();
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
