package io.github.favasur.fullslabs.config;

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.favasur.fullslabs.FullSlabs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for the slab-placement overlay and the "tilted" vertical slab set.
 * Ported from the original MidnightConfig-based config to a small JSON file so the port has no
 * extra library dependency. The file lives in the loader's config directory
 * ({@link #configDir}, set by the loader entry point).
 */
public final class Config {

	/** Set by the loader entry point (e.g. FMLPaths.CONFIGDIR for NeoForge). */
	public static Path configDir = Path.of("config");

	public static List<ResourceLocation> tiltedSlabs = Lists.newArrayList(
			ResourceLocation.withDefaultNamespace("smooth_stone_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "white_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "orange_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "magenta_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "light_blue_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "yellow_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "lime_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "pink_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "gray_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "light_gray_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "cyan_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "purple_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "blue_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "brown_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "green_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "red_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "black_stained_glass_slab"),
			ResourceLocation.fromNamespaceAndPath("mo_glass", "tinted_glass_slab")
	);
	public static int edgeOpacity = 255;
	public static String edgeColor = "#FFFFFF";
	public static int fillOpacity = 63;
	public static String fillColor = "#007FFF";

	private static int cachedEdgeColor = -1;
	private static int cachedFillColor = 1056997375;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private Config() {
	}

	public static boolean isTilted(Block block) {
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
		return tiltedSlabs.contains(id);
	}

	public static int edgeColor() {
		return cachedEdgeColor;
	}

	public static int fillColor() {
		return cachedFillColor;
	}

	private static Path file() {
		return configDir.resolve("fullslabs.json");
	}

	public static void load() {
		Path path = file();
		if (Files.isRegularFile(path)) {
			try {
				Data data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
				if (data != null) {
					if (data.tiltedSlabs != null) tiltedSlabs = data.tiltedSlabs;
					if (data.edgeOpacity != null) edgeOpacity = data.edgeOpacity;
					if (data.edgeColor != null) edgeColor = data.edgeColor;
					if (data.fillOpacity != null) fillOpacity = data.fillOpacity;
					if (data.fillColor != null) fillColor = data.fillColor;
				}
			} catch (IOException | RuntimeException e) {
				FullSlabs.LOGGER.warn("Failed to load fullslabs config: {}", e.toString());
			}
		}
		reload();
	}

	/** Client-side re-read after the server pushes its config; keeps the parsed colors fresh. */
	public static void loadClient() {
		load();
	}

	public static void save() {
		Data data = new Data();
		data.tiltedSlabs = tiltedSlabs;
		data.edgeOpacity = edgeOpacity;
		data.edgeColor = edgeColor;
		data.fillOpacity = fillOpacity;
		data.fillColor = fillColor;
		try {
			Files.createDirectories(configDir);
			Files.writeString(file(), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (IOException e) {
			FullSlabs.LOGGER.warn("Failed to save fullslabs config: {}", e.toString());
		}
	}

	public void writeChanges() {
		save();
		reload();
	}

	public void loadValuesFromJson() {
		reload();
	}

	private static void reload() {
		cachedEdgeColor = parseColor(edgeOpacity, edgeColor);
		cachedFillColor = parseColor(fillOpacity, fillColor);
	}

	private static int parseColor(int opacity, String color) {
		String hex = color.replaceAll("[^0-9A-Fa-f]", "");
		if (hex.length() > 6) {
			hex = hex.substring(0, 6);
		} else if (hex.length() == 3) {
			hex = String.valueOf(hex.charAt(0)) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
		} else if (hex.length() < 6) {
			return -1;
		}
		int r = Integer.parseInt(hex.substring(0, 2), 16);
		int g = Integer.parseInt(hex.substring(2, 4), 16);
		int b = Integer.parseInt(hex.substring(4, 6), 16);
		return opacity << 24 | r << 16 | g << 8 | b;
	}

	/** Serialized shape of the config file. */
	private static final class Data {
		List<ResourceLocation> tiltedSlabs;
		Integer edgeOpacity;
		String edgeColor;
		Integer fillOpacity;
		String fillColor;
	}
}
