package net.buildertools;

import net.buildertools.client.ClientEvents;
import net.buildertools.client.OffGridBlockRenderer;
import net.buildertools.client.SelectionRenderer;
import net.buildertools.network.ModPackets;
import net.buildertools.registry.ModEntities;
import net.buildertools.registry.ModItems;
import net.buildertools.registry.ModSounds;
import net.buildertools.server.ServerEvents;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(BuilderToolsMod.MODID)
public class BuilderToolsMod {
    public static final String MODID = "buildertools";

    public BuilderToolsMod(FMLJavaModLoadingContext context) {
        BusGroup modBusGroup = context.getModBusGroup();
        ModEntities.ENTITIES.register(modBusGroup);
        ModItems.ITEMS.register(modBusGroup);
        ModSounds.SOUND_EVENTS.register(modBusGroup);
        // The channel is built eagerly as a static field of ModPackets; nothing to do at setup.
        BuildCreativeModeTabContentsEvent.BUS.addListener(this::addCreative);

        // Server-side safety net: keeps the tools from breaking/placing/interacting even if a
        // misbehaving client sends the vanilla packets anyway. Client cancels these first.
        MinecraftForge.EVENT_BUS.register(ServerEvents.class);

        if (FMLEnvironment.dist.isClient()) {
            // The builder gizmo renderer is hooked into the vanilla DebugRenderer by
            // DebugRendererMixin; ClientEvents handles all the tool interactions.
            EntityRenderersEvent.RegisterRenderers.BUS.addListener(event ->
                    event.registerEntityRenderer(ModEntities.OFF_GRID_BLOCK.get(), OffGridBlockRenderer::new));
            MinecraftForge.EVENT_BUS.register(ClientEvents.class);
        }
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
