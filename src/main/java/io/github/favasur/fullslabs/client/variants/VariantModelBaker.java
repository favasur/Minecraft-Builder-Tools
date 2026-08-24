package io.github.favasur.fullslabs.client.variants;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.variants.BaseBlockTextures;
import io.github.favasur.fullslabs.variants.VariantGeometry;
import io.github.favasur.fullslabs.variants.VariantGeometry.Box;
import io.github.favasur.fullslabs.variants.VariantRegistry;
import io.github.favasur.fullslabs.variants.VariantRegistry.VariantEntry;
import io.github.favasur.fullslabs.variants.VariantRegistry.VariantKind;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Bakes the generated slab / stair / roof-slope variants into the {@link ModelEvent.ModifyBakingResult}
 * map at client model-reload time. Each state's model JSON is generated from the vanilla-exact (or
 * custom) element boxes with the base block's concrete textures inlined, then baked and injected
 * under its {@code BlockModelShaper.stateToModelLocation} key. Item inventory models are injected
 * under {@code ModelResourceLocation.inventory}.
 */
public final class VariantModelBaker {

	private VariantModelBaker() {
	}

	public static void bake(ModelEvent.ModifyBakingResult event, ModelBaker baker) {
		Map<ModelResourceLocation, BakedModel> models = event.getModels();
		for (VariantEntry entry : VariantRegistry.entries()) {
			BaseBlockTextures textures = BaseBlockTextures.extract(entry.base());
			if (textures == null) {
				FullSlabs.LOGGER.debug("FullSlabs: no usable cube model for {}, skipping variants", entry.base());
				continue;
			}
			Block block = entry.block();
			BakedModel canonicalBaked = null;
			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				ModelResourceLocation mrl = BlockModelShaper.stateToModelLocation(state);
				BakedModel baked;
				if (entry.kind() == VariantKind.SLAB && state.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
					// double slab renders exactly like the base block
					baked = models.get(BlockModelShaper.stateToModelLocation(entry.base().defaultBlockState()));
					if (baked == null) {
						baked = bakeModel(baker, VariantGeometry.modelJson(
								VariantGeometry.slabBoxes("double"), textures.top, textures.bottom, textures.side));
					}
				} else {
					List<Box> boxes = VariantRegistry.boxesFor(entry.kind(), state);
					baked = bakeModel(baker, VariantGeometry.modelJson(boxes, textures.top, textures.bottom, textures.side));
				}
				models.put(mrl, baked);
				if (state.equals(VariantRegistry.canonicalState(block, entry.kind()))) {
					canonicalBaked = baked;
				}
			}
			if (canonicalBaked == null) {
				canonicalBaked = models.get(BlockModelShaper.stateToModelLocation(VariantRegistry.canonicalState(block, entry.kind())));
			}
			if (canonicalBaked != null) {
				models.put(ModelResourceLocation.inventory(entry.id()), canonicalBaked);
			}
		}
	}

	private static BakedModel bakeModel(ModelBaker baker, String json) {
		try {
			BlockModel model = BlockModel.fromStream(new StringReader(json));
			model.resolveParents(baker::getModel);
			return model.bake(baker, baker.getModelTextureGetter(), BlockModelRotation.by(0, 0));
		} catch (Exception e) {
			throw new RuntimeException("FullSlabs: could not bake variant model", e);
		}
	}
}
