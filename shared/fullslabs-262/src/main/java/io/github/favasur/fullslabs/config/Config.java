package io.github.favasur.fullslabs.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import net.minecraft.client.Minecraft;

/**
 * FullSlabs client configuration (loader-neutral). A small {@code fullslabs.json} file in the
 * game's {@code config} directory holds the placement-overlay colors; it is read lazily on the
 * render thread the first time the overlay draws and written with defaults when absent. Kept
 * free of loader APIs so the shared graft core compiles on every loader.
 */
public final class Config {

    /** ARGB fill color of the slab-placement overlay. */
    public static int fillColor = 0x59_00BFFF;

    /** ARGB edge color of the slab-placement overlay. */
    public static int edgeColor = 0xE6_FFFFFF;

    private static boolean loaded;

    private Config() {
    }

    /** Loads (or creates) the config file. Safe to call every frame; loads once. */
    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            File dir = new File(Minecraft.getInstance().gameDirectory, "config");
            File file = new File(dir, "fullslabs.json");
            if (file.isFile()) {
                JsonObject root = JsonParser.parseReader(new FileReader(file)).getAsJsonObject();
                if (root.has("fillColor")) {
                    fillColor = root.get("fillColor").getAsInt();
                }
                if (root.has("edgeColor")) {
                    edgeColor = root.get("edgeColor").getAsInt();
                }
                return;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            JsonObject root = new JsonObject();
            root.addProperty("fillColor", fillColor);
            root.addProperty("edgeColor", edgeColor);
            try (FileWriter writer = new FileWriter(file)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
        } catch (Exception ignored) {
            // Config is best-effort; the defaults remain in effect.
        }
    }
}
