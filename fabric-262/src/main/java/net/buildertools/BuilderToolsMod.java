package net.buildertools;

import net.buildertools.network.ModPackets;
import net.buildertools.registry.ModItems;
import net.buildertools.registry.ModSounds;
import net.buildertools.server.ServerEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
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

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
            output.accept(ModItems.SELECTION_TOOL);
            output.accept(ModItems.ENTITY_TOOL);
            output.accept(ModItems.RULER_TOOL);
            output.accept(ModItems.LASER_TOOL);
            output.accept(ModItems.SCATTER_TOOL);
            output.accept(ModItems.SMOOTH_TOOL);
            output.accept(ModItems.PAINT_TOOL);
        });
    }
}
