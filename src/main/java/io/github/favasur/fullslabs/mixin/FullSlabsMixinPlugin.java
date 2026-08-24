package io.github.favasur.fullslabs.mixin;

import net.buildertools.util.ApiCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Version-gates the FullSlabs mixins (common + NeoForge configs) so the same jar loads on every
 * 1.21.x minor version (1.21.1 … 1.21.11). Several of the original 1.21.9-era mixins were adapted
 * to 1.21.1 signatures whose targets (private map builders, chunk setBlockState, the breaking
 * particle method, model-baker constructors, …) were renamed or reworked in later 1.21.x versions;
 * each mixin is applied only when {@link ApiCompat} confirms its target exists on the running
 * version, degrading gracefully (e.g. missing step-sound/particle polish) instead of crashing.
 */
public final class FullSlabsMixinPlugin implements IMixinConfigPlugin {

	boolean shouldApply(String mixinClassName) {
		return switch (mixinClassName) {
			case "io.github.favasur.fullslabs.mixin.BlockEntityTypeAccessor" -> ApiCompat.blockEntityTypeCtorV1();
			case "io.github.favasur.fullslabs.mixin.HoneycombItemMixin" -> ApiCompat.honeycombMethod();
			case "io.github.favasur.fullslabs.mixin.WeatheringCopperMixin" -> ApiCompat.weatherMethod();
			case "io.github.favasur.fullslabs.mixin.LevelChunkMixin" -> ApiCompat.levelChunkSetBlockStateV1();
			case "io.github.favasur.fullslabs.mixin.EntityMixin" -> ApiCompat.entityVibrationStepSounds();
			case "io.github.favasur.fullslabs.mixin.client.BlockRenderDispatcherMixin" -> ApiCompat.breakingTexture5Arg();
			case "io.github.favasur.fullslabs.mixin.client.LevelRendererMixin" -> ApiCompat.renderHitOutlineV1();
			case "io.github.favasur.fullslabs.neoforge.mixin.AxeItemMixin" -> ApiCompat.axeEvaluateNewBlockState();
			case "io.github.favasur.fullslabs.neoforge.mixin.EntityMixin" -> ApiCompat.spawnSprintParticle();
			case "io.github.favasur.fullslabs.neoforge.mixin.client.ClientLevelMixin" -> ApiCompat.breakingBlockEffect3Arg();
			default -> true;
		};
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return shouldApply(mixinClassName);
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
