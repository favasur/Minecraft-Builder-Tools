package net.buildertools;

import net.buildertools.network.ModPackets;
import net.buildertools.registry.ModItems;
import net.buildertools.registry.ModSounds;
import net.buildertools.server.ServerEvents;
import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.SlabRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.world.item.CreativeModeTabs;

public class BuilderToolsMod implements ModInitializer {
    public static final String MODID = "buildertools";

    @Override
    public void onInitialize() {
        // Item and sound registration happen in the static initializers of ModItems/ModSounds
        // (direct Registry.register calls), which run when the classes are first touched here.
        ModPackets.register();

        // Server-side safety net: keeps the tools from breaking/placing/interacting even if a
        // misbehaving client sends the vanilla packets anyway. Client cancels these first.
        ServerEvents.register();
        FullSlabs.init();
        SlabRegistry.init();

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(content -> {
            content.accept(ModItems.SELECTION_TOOL);
            content.accept(ModItems.ENTITY_TOOL);
            content.accept(ModItems.RULER_TOOL);
            content.accept(ModItems.LASER_TOOL);
            content.accept(ModItems.SCATTER_TOOL);
            content.accept(ModItems.SMOOTH_TOOL);
            content.accept(ModItems.PAINT_TOOL);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
        });
    }
}
