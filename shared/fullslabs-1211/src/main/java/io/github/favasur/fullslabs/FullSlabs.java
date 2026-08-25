package io.github.favasur.fullslabs;

import io.github.favasur.fullslabs.config.Config;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Shared FullSlabs bootstrap used by the Fabric and Forge 1.21.1 adapters. */
public final class FullSlabs {
    public static final String MODID = "fullslabs";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    private FullSlabs() {
    }

    public static void init() {
        Config.load();
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static String verticalPath(ResourceLocation parent) {
        return "vertical/" + parent.toString().replace(':', '/');
    }
}
