package io.github.favasur.fullslabs.neoforge;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.compat.FullSlabsCompat;
import io.github.favasur.fullslabs.config.Config;
import io.github.favasur.fullslabs.config.neoforge.ControlsImpl;
import io.github.favasur.fullslabs.neoforge.client.FullSlabsNeoForgeClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge entry point for the bundled FullSlabs mod (modid "fullslabs", shipped inside the
 * Builder Tools jar). Ported from FullSlabs 4.0.3 (MC 1.21.9) to NeoForge 1.21.1.
 */
@Mod(FullSlabs.MODID)
public final class FullSlabsNeoForge {

	/** The block-entity model data property carrying the two halves of a mixed slab. */
	public static final ModelProperty<MixedSlabBlockEntity.ModelContext> MIXED_CONTEXT_MODEL_PROPERTY = new ModelProperty<>();

	public FullSlabsNeoForge(IEventBus modBus) {
		Config.configDir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
		FullSlabs.init(modBus);
		FullSlabsCompat.init();
		io.github.favasur.fullslabs.variants.VariantRegistry.register(modBus);

		if (FMLEnvironment.dist.isClient()) {
			modBus.addListener((RegisterKeyMappingsEvent event) -> ControlsImpl.register(event));
			modBus.addListener((ModelEvent.RegisterAdditional event) -> FullSlabsNeoForgeClient.registerAdditional(event));
			modBus.addListener((ModelEvent.ModifyBakingResult event) -> FullSlabsNeoForgeClient.modifyBakingResult(event));
			modBus.addListener(FullSlabsNeoForgeClient::clientSetup);
			NeoForge.EVENT_BUS.addListener(FullSlabsNeoForgeClient::renderOverlay);
			Config.loadClient();
		}
	}
}
