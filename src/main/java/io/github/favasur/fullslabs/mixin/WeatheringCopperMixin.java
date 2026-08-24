package io.github.favasur.fullslabs.mixin;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.block.WeatheringCopper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In 1.21.1 {@link WeatheringCopper}'s oxidation table is an {@code ImmutableBiMap}; the mod needs
 * to register its oxidizable vertical slabs in it, so the map is rebuilt as a mutable
 * {@code HashBiMap}. NeoForge's data-map patch restructures the map builder into a private static
 * {@code lambda$static$0} method (vanilla 1.21.1 names it {@code method_34740}), which is what the
 * running game actually contains.
 */
@Mixin(value = WeatheringCopper.class)
public interface WeatheringCopperMixin {
	@ModifyReturnValue(method = "lambda$static$0()Lcom/google/common/collect/BiMap;", at = @At("RETURN"))
	private static BiMap createOxidationLevelIncreasesMap(BiMap original) {
		return HashBiMap.create(original);
	}
}
