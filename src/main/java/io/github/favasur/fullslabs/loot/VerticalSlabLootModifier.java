package io.github.favasur.fullslabs.loot;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.common.loot.LootModifier;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The vertical slab's loot table is its parent slab's table (so mining it yields the parent slab
 * item). That table only doubles the count for the parent's own {@code double} state; a FULL
 * vertical slab is made of two halves, so this modifier adds one extra parent-slab drop when a
 * full vertical slab is destroyed (by mining, explosion, etc.).
 */
public final class VerticalSlabLootModifier extends LootModifier {

	public static final MapCodec<VerticalSlabLootModifier> CODEC = RecordCodecBuilder.mapCodec(
			instance -> codecStart(instance).apply(instance, VerticalSlabLootModifier::new));

	public static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> REGISTER =
			DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, FullSlabs.MODID);
	public static final DeferredHolder<MapCodec<? extends IGlobalLootModifier>, MapCodec<VerticalSlabLootModifier>> SUPPLIER =
			REGISTER.register("vertical_slab_full", () -> CODEC);

	private VerticalSlabLootModifier(LootItemCondition[] conditions) {
		super(conditions);
	}

	@Override
	public MapCodec<? extends IGlobalLootModifier> codec() {
		return CODEC;
	}

	@Override
	protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
		BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
		if (state != null && state.getBlock() instanceof VerticalSlabBlock vertical
				&& state.getValue(VerticalSlabBlock.TYPE) == VerticalSlabBlock.VerticalType.FULL) {
			generatedLoot.add(new ItemStack(vertical.parent));
		}
		return generatedLoot;
	}
}
