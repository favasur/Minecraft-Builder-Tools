package io.github.favasur.fullslabs;

import io.github.favasur.fullslabs.config.Config;
import io.github.favasur.fullslabs.config.Controls;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class FullSlabs {
	public static final String MODID = "fullslabs";
	public static final Logger LOGGER = LogManager.getLogger("fullslabs");

	private FullSlabs() {
	}

	/** Common (non-loader) setup. Runs at mod construction. */
	public static void init(IEventBus modBus) {
		Config.load();
		SlabRegistry.init();
		SlabRegistry.register(modBus);
		Controls.registerPackets(modBus);
		// Server-side: full vertical slabs drop an extra parent-slab item (see the modifier).
		io.github.favasur.fullslabs.loot.VerticalSlabLootModifier.REGISTER.register(modBus);
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MODID, path);
	}

	public static String verticalPath(ResourceLocation parent) {
		return "vertical/" + parent.toString().replace(':', '/');
	}
}
