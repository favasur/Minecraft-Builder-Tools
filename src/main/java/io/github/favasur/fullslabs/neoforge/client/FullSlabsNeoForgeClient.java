package io.github.favasur.fullslabs.neoforge.client;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.client.BlockFaceOverlay;
import io.github.favasur.fullslabs.client.FullSlabsClient;
import io.github.favasur.fullslabs.config.Controls;
import io.github.favasur.fullslabs.client.models.MixedSlabModel;
import io.github.favasur.fullslabs.client.models.VerticalSlabModel;
import java.lang.reflect.Constructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * NeoForge client glue for FullSlabs on 1.21.1: registers the vertical-slab template geometry as
 * additional models, injects the dynamically baked vertical/mixed slab models into the baking
 * result, and draws the slab-placement overlay.
 */
public final class FullSlabsNeoForgeClient {

    private FullSlabsNeoForgeClient() {
    }

    /** Registers the template geometry so {@link ModelBakery#getModel} can load it. */
    public static void registerAdditional(ModelEvent.RegisterAdditional event) {
        for (ResourceLocation id : VerticalSlabModel.TEMPLATES) {
            event.register(ModelResourceLocation.standalone(id));
        }
    }

    /** Replaces the vertical/mixed slab block-state models with the dynamically baked ones. */
    public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
        // The dynamic baking relies on the 1.21.1 ModelBakery$ModelBakerImpl constructor exposed by
        // ModelBakerImplAccessor; on later 1.21.x minors the baking pipeline changed and the mixin is
        // skipped, so fall back to the plain (undecorated) models instead of crashing.
        if (!net.buildertools.util.ApiCompat.modelBakerImplCtor()) {
            return;
        }
        ModelBakery bakery = event.getModelBakery();
        ModelBaker baker = createBaker(bakery, event);
        for (VerticalSlabBlock vertical : VerticalSlabBlock.MAP_VIEW.values()) {
            for (BlockState state : vertical.getStateDefinition().getPossibleStates()) {
                BlockState parentState = VerticalSlabModel.parentState(state);
                BakedModel parent = event.getModels().get(BlockModelShaper.stateToModelLocation(parentState));
                if (parent == null) {
                    FullSlabs.LOGGER.warn("FullSlabs: parent model for {} was not baked!", parentState);
                    continue;
                }
                BakedModel baked = VerticalSlabModel.bake(state, baker, event.getTextureGetter(), parent);
                event.getModels().put(BlockModelShaper.stateToModelLocation(state), baked);
            }
        }		for (BlockState state : SlabRegistry.MIXED_SLAB.getStateDefinition().getPossibleStates()) {
			event.getModels().put(BlockModelShaper.stateToModelLocation(state), MixedSlabModel.INSTANCE);
		}
		// Generated slab / stair / roof-slope variants for every full-cube block in the game.
		io.github.favasur.fullslabs.client.variants.VariantModelBaker.bake(event, baker);
	}

    public static void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        FullSlabsClient.init();
        net.neoforged.bus.api.IEventBus gameBus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        gameBus.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post e) -> Controls.onClientTick(Minecraft.getInstance()));
    }

    /**
     * Instantiates the package-private {@code ModelBakery$ModelBakerImpl} through reflection
     * (the 1.21.1 constructor {@code (ModelBakery, TextureGetter, ModelResourceLocation)}); a mixin
     * accessor cannot return that type because the class is not accessible outside its package.
     */
    private static ModelBaker createBaker(ModelBakery bakery, ModelEvent.ModifyBakingResult event) {
        try {
            Class<?> impl = Class.forName("net.minecraft.client.resources.model.ModelBakery$ModelBakerImpl");
            Constructor<?> ctor = impl.getDeclaredConstructor(
                    ModelBakery.class, ModelBakery.TextureGetter.class, ModelResourceLocation.class);
            ctor.setAccessible(true);
            return (ModelBaker) ctor.newInstance(
                    bakery,
                    (ModelBakery.TextureGetter) (location, material) -> event.getTextureGetter().apply(material),
                    ModelBakery.MISSING_MODEL_VARIANT);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("FullSlabs: could not create model baker", e);
        }
    }

    /** Draws the slab-placement face overlay after entities. */
    public static void renderOverlay(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) {
            return;
        }
        BlockFaceOverlay.renderFaceOverlay(event.getCamera());
    }
}
