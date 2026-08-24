package io.github.favasur.fullslabs.mixin;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.HoneycombItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In 1.21.1 {@link HoneycombItem}'s wax table is an {@code ImmutableBiMap}; the mod needs to
 * register its waxable vertical slabs in it, so the map is rebuilt as a mutable {@code HashBiMap}.
 * NeoForge's data-map patch restructures the map builder into a private static
 * {@code lambda$static$0} method (vanilla 1.21.1 names it {@code method_34723}), which is what the
 * running game actually contains.
 */
@Mixin(value = HoneycombItem.class)
public class HoneycombItemMixin {
	@ModifyReturnValue(method = "lambda$static$0()Lcom/google/common/collect/BiMap;", at = @At("RETURN"))
	private static BiMap createUnwaxedToWaxedMap(BiMap original) {
		return HashBiMap.create(original);
	}
}
