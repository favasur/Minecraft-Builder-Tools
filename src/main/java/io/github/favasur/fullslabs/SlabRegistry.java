package io.github.favasur.fullslabs;

import com.google.common.collect.BiMap;
import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.OxidizableVerticalSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.handlers.MixedHandler;
import io.github.favasur.fullslabs.handlers.MixedHandlers;
import io.github.favasur.fullslabs.handlers.OxidizableMixedHandler;
import io.github.favasur.fullslabs.handlers.VanillaMixedHandler;
import io.github.favasur.fullslabs.mixin.BlockEntityTypeAccessor;
import io.github.favasur.fullslabs.util.Utility;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.WeatheringCopperSlabBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Registers the mod's blocks and, for every {@link SlabBlock} in the game (vanilla and other
 * mods), a matching {@link VerticalSlabBlock} generated from the slab's own model/textures.
 * Ported from FullSlabs (Micalobia) to NeoForge 1.21.1.
 */
public final class SlabRegistry {

	private static final Map<Class<? extends SlabBlock>, VerticalFactory> MAPPING = new HashMap<>();
	private static final Map<Class<? extends SlabBlock>, PairConsumer> POST_INIT = new HashMap<>();

	private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, FullSlabs.MODID);
	private static final DeferredRegister<Block> GENERATED = DeferredRegister.create(Registries.BLOCK, FullSlabs.MODID);
	private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FullSlabs.MODID);

	public static final DeferredHolder<Block, MixedSlabBlock> MIXED_SLAB_SUPPLIER =
			registerBlock("mixed_slab", MixedSlabBlock::new);
	public static MixedSlabBlock MIXED_SLAB;
	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MixedSlabBlockEntity>> MIXED_SLAB_ENTITY =
			BLOCK_ENTITIES.register("mixed_slab", () -> BlockEntityTypeAccessor.constructor(
					MixedSlabBlockEntity::new, Set.of(MIXED_SLAB_SUPPLIER.get()), null));

	private SlabRegistry() {
	}

	private static <T extends Block> DeferredHolder<Block, T> registerBlock(String id, Function<BlockBehaviour.Properties, T> func) {
		return registerBlock(id, func, BlockBehaviour.Properties::of);
	}

	private static <T extends Block> DeferredHolder<Block, T> registerBlock(String id, Function<BlockBehaviour.Properties, T> func, Supplier<BlockBehaviour.Properties> settings) {
		return BLOCKS.register(id, () -> func.apply(settings.get()));
	}

	public static void init() {
		registerVanilla();
		seedExistingSlabs();
	}

	/** Attach the deferred registers to the mod event bus. */
	public static void register(IEventBus modBus) {
		BLOCKS.register(modBus);
		BLOCK_ENTITIES.register(modBus);
		GENERATED.register(modBus);
		modBus.addListener((FMLCommonSetupEvent event) -> {
			event.enqueueWork(() -> {
				MIXED_SLAB = MIXED_SLAB_SUPPLIER.get();
				VerticalSlabBlock.MAP_VIEW.keySet().stream()
						.filter(slab -> POST_INIT.containsKey(slab.getClass()))
						.forEach(slab -> POST_INIT.get(slab.getClass()).consume(slab, VerticalSlabBlock.getVertical(slab)));
			});
		});
	}

	private static void registerVanilla() {
		registerVertical(SlabBlock.class, VerticalSlabBlock::new);
		MixedHandlers.register(SlabBlock.class, VanillaMixedHandler.INSTANCE);
		registerVertical(WeatheringCopperSlabBlock.class, OxidizableVerticalSlabBlock::new, SlabRegistry::registerOxidizableSlabs);
		MixedHandlers.register(WeatheringCopperSlabBlock.class, OxidizableMixedHandler.INSTANCE);
	}

	private static void registerDebug() {
		registerBlock("debug", Block::new);
		registerBlock("debug_slab", SlabBlock::new);
	}

	public static void registerOxidizableBlockPair(Block less, Block more) {
		Objects.requireNonNull(less, "Oxidizable block cannot be null!");
		Objects.requireNonNull(more, "Oxidizable block cannot be null!");
		WeatheringCopper.NEXT_BY_BLOCK.get().forcePut(less, more);
	}

	public static void registerWaxableBlockPair(Block unwaxed, Block waxed) {
		Objects.requireNonNull(unwaxed, "Unwaxed block cannot be null!");
		Objects.requireNonNull(waxed, "Waxed block cannot be null!");
		HoneycombItem.WAXABLES.get().forcePut(unwaxed, waxed);
	}

	private static void registerOxidizableSlabs(SlabBlock slab, VerticalSlabBlock vertical) {
		Optional<Block> less = WeatheringCopper.getPrevious(slab);
		Optional<Block> more = WeatheringCopper.getNext(slab);
		Optional<Block> lessWaxed = less.flatMap(Utility::getWaxed);
		Optional<Block> slabWaxed = Utility.getWaxed(slab);
		Optional<Block> moreWaxed = more.flatMap(Utility::getWaxed);
		Optional<VerticalSlabBlock> lessVertical = less.flatMap(VerticalSlabBlock::tryGetVertical);
		Optional<VerticalSlabBlock> moreVertical = more.flatMap(VerticalSlabBlock::tryGetVertical);
		Optional<VerticalSlabBlock> lessWaxedVertical = lessWaxed.flatMap(VerticalSlabBlock::tryGetVertical);
		Optional<VerticalSlabBlock> slabWaxedVertical = slabWaxed.flatMap(VerticalSlabBlock::tryGetVertical);
		Optional<VerticalSlabBlock> moreWaxedVertical = moreWaxed.flatMap(VerticalSlabBlock::tryGetVertical);
		lessVertical.ifPresent(_less -> {
			registerOxidizableBlockPair(_less, vertical);
			lessWaxedVertical.ifPresent(_waxed -> {
				registerWaxableBlockPair(_less, _waxed);
				MixedHandlers.register(_waxed, OxidizableMixedHandler.INSTANCE);
			});
		});
		moreVertical.ifPresent(_more -> {
			registerOxidizableBlockPair(vertical, _more);
			moreWaxedVertical.ifPresent(_waxed -> {
				registerWaxableBlockPair(_more, _waxed);
				MixedHandlers.register(_waxed, OxidizableMixedHandler.INSTANCE);
			});
		});
		slabWaxedVertical.ifPresent(_waxed -> {
			registerWaxableBlockPair(vertical, _waxed);
			MixedHandlers.register(_waxed, OxidizableMixedHandler.INSTANCE);
		});
	}

	public static <S extends SlabBlock, V extends VerticalSlabBlock> void registerVertical(Class<S> slabClass, VerticalFactory<S, V> factory) {
		registerVertical(slabClass, factory, null);
	}

	public static <S extends SlabBlock, V extends VerticalSlabBlock> void registerVertical(Class<S> slabClass, VerticalFactory<S, V> factory, PairConsumer<S, V> listener) {
		VerticalFactory<S, V> old = MAPPING.putIfAbsent(slabClass, factory);
		if (old != null) {
			throw new IllegalArgumentException("That slab class has already been registered!");
		}
		if (listener != null) {
			POST_INIT.put(slabClass, listener);
		}
	}

	/** Seeds a vertical variant for every slab block registered so far (vanilla + earlier mods). */
	private static void seedExistingSlabs() {
		List<Block> slabs = BuiltInRegistries.BLOCK.stream().filter(SlabBlock.class::isInstance).toList();
		for (Block block : slabs) {
			tryRegisterVertical(BuiltInRegistries.BLOCK.getKey(block), block);
		}
	}

	public static void tryRegisterVertical(ResourceLocation id, Block block) {
		if (!(block instanceof SlabBlock slab)) {
			return;
		}
		VerticalFactory factory = MAPPING.get(slab.getClass());
		if (factory == null) {
			FullSlabs.LOGGER.warn("{} ({}) failed to register a vertical!", slab, slab.getClass().getSimpleName());
			return;
		}
		ResourceLocation verticalId = FullSlabs.id(FullSlabs.verticalPath(id));
		GENERATED.register(verticalId.getPath(), () -> {
			BlockBehaviour.Properties settings = BlockBehaviour.Properties.ofFullCopy(slab).dropsLike(slab);
			return factory.create(slab, settings);
		});
	}

	public interface VerticalFactory<S extends SlabBlock, V extends VerticalSlabBlock> {
		V create(S slab, BlockBehaviour.Properties properties);
	}

	public interface PairConsumer<S extends SlabBlock, V extends VerticalSlabBlock> {
		void consume(S slab, V vertical);
	}
}
