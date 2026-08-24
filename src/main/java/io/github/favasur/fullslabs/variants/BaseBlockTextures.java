package io.github.favasur.fullslabs.variants;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.favasur.fullslabs.FullSlabs;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;

/**
 * Client-side extraction of a base block's concrete texture paths from its block-model JSON.
 * Walks the parent chain exactly like the model loader does: the first model that declares
 * {@code elements} supplies the geometry, texture dictionaries merge child-over-parent, and
 * {@code #reference} variables resolve against the merged dictionary.
 */
public final class BaseBlockTextures {

	public final String top;
	public final String bottom;
	public final String side;

	private BaseBlockTextures(String top, String bottom, String side) {
		this.top = top;
		this.bottom = bottom;
		this.side = side;
	}

	/** Returns the extracted textures, or {@code null} when the block has no usable cube model. */
	public static BaseBlockTextures extract(Block block) {
		try {
			ResourceLocation model = BlockModelShaper.stateToModelLocation(block.defaultBlockState()).id();
			JsonObject root = loadModel(model);
			if (root == null) {
				return null;
			}
			if (!isFullCube(root)) {
				return null;
			}
			Map<String, String> textures = new HashMap<>();
			mergeTextures(textures, root);
			if (textures.isEmpty()) {
				return null;
			}
			String all = resolve(textures, "all");
			String top = resolve(textures, "top");
			String bottom = resolve(textures, "bottom");
			String side = resolve(textures, "side");
			if (all != null) {
				top = all;
				bottom = all;
				side = all;
			} else {
				side = firstNonNull(side, top, bottom);
				top = firstNonNull(top, side, bottom);
				bottom = firstNonNull(bottom, side, top);
			}
			if (top == null || bottom == null || side == null) {
				return null;
			}
			return new BaseBlockTextures(top, bottom, side);
		} catch (Exception e) {
			FullSlabs.LOGGER.debug("FullSlabs: could not extract textures for {}: {}", block, e.toString());
			return null;
		}
	}

	private static String firstNonNull(String... values) {
		for (String v : values) {
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	/** Loads {@code <ns>:models/<path>.json} as a JSON object, or null. */
	private static JsonObject loadModel(ResourceLocation modelId) {
		ResourceLocation file = ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open()) {
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	/** Resolves the parent model of the given model JSON, or null. */
	private static JsonObject parentOf(JsonObject model) {
		JsonElement parent = model.get("parent");
		if (parent == null || parent.isJsonNull()) {
			return null;
		}
		String path = parent.getAsString();
		int colon = path.indexOf(':');
		String namespace = colon >= 0 ? path.substring(0, colon) : "minecraft";
		String modelPath = colon >= 0 ? path.substring(colon + 1) : path;
		return loadModel(ResourceLocation.fromNamespaceAndPath(namespace, modelPath));
	}

	/** First model in the chain (starting from the child) that declares elements, or null. */
	private static JsonObject effectiveModel(JsonObject root) {
		JsonObject model = root;
		int depth = 0;
		while (model != null && depth++ < 16) {
			if (model.has("elements")) {
				return model;
			}
			model = parentOf(model);
		}
		return null;
	}

	private static boolean isFullCube(JsonObject root) {
		JsonObject model = effectiveModel(root);
		if (model == null) {
			return false;
		}
		JsonElement elements = model.get("elements");
		if (!elements.isJsonArray() || elements.getAsJsonArray().size() != 1) {
			return false;
		}
		JsonObject element = elements.getAsJsonArray().get(0).getAsJsonObject();
		double[] from = readCoords(element.get("from"));
		double[] to = readCoords(element.get("to"));
		return from != null && to != null
				&& from[0] == 0 && from[1] == 0 && from[2] == 0
				&& to[0] == 16 && to[1] == 16 && to[2] == 16;
	}

	private static double[] readCoords(JsonElement coords) {
		if (coords == null || !coords.isJsonArray()) {
			return null;
		}
		JsonArray array = coords.getAsJsonArray();
		if (array.size() != 3) {
			return null;
		}
		return new double[]{array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
	}

	/** Merges texture dictionaries child-over-parent into {@code merged}. */
	private static void mergeTextures(Map<String, String> merged, JsonObject model) {
		JsonObject parent = parentOf(model);
		if (parent != null) {
			mergeTextures(merged, parent);
		}
		JsonElement textures = model.get("textures");
		if (textures != null && textures.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : textures.getAsJsonObject().entrySet()) {
				if (entry.getValue().isJsonPrimitive()) {
					merged.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
		}
	}

	private static String resolve(Map<String, String> textures, String name) {
		String value = textures.get(name);
		int hops = 0;
		while (value != null && value.startsWith("#") && hops++ < 8) {
			value = textures.get(value.substring(1));
		}
		if (value == null || value.startsWith("#")) {
			return null;
		}
		return value.contains(":") ? value : "minecraft:" + value;
	}
}
