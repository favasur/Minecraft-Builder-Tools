package io.github.favasur.fullslabs.variants;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.variants.RoofSlopeBlock.Kind;
import io.github.favasur.fullslabs.variants.VariantGeometry.Box;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Automatically adds the missing slab / stair / roof-slope variants for every full-cube block in
 * the game, including blocks from other mods. Runs at the lowest priority of the BLOCK
 * {@link RegisterEvent}, so it sees every block registered by every mod. The three Hytale Build
 * roof slope shapes (slope, outer corner, inner corner) are always generated. All variants are
 * registered in a dedicated creative tab.
 */
public final class VariantRegistry {

	public enum VariantKind {
		SLAB("slab", "Slab"),
		STAIRS("stairs", "Stairs"),
		SLOPE("slope", "Slope"),
		SLOPE_OUTER("slope_outer", "Slope Outer Corner"),
		SLOPE_INNER("slope_inner", "Slope Inner Corner");

		private final String path;
		private final String suffix;

		VariantKind(String path, String suffix) {
			this.path = path;
			this.suffix = suffix;
		}

		public String path() {
			return path;
		}

		public String suffix() {
			return suffix;
		}
	}

	/** One generated variant: the variant block, its base block and its kind. */
	public record VariantEntry(ResourceLocation id, Block block, Block base, VariantKind kind) {
	}

	private static final List<VariantEntry> ENTRIES = new ArrayList<>();
	private static final List<Object[]> PENDING_ITEMS = new ArrayList<>(); // {id, block, base, kind}

	private static final DeferredRegister<CreativeModeTab> TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FullSlabs.MODID);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> VARIANTS_TAB = TABS.register(
			"variants", () -> CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.fullslabs.variants"))
					.icon(() -> new ItemStack(Items.SMOOTH_STONE_SLAB))
					.build());

	private VariantRegistry() {
	}

	public static void register(IEventBus modBus) {
		TABS.register(modBus);
		modBus.register(new VariantRegistration());
		// BuildCreativeModeTabContentsEvent is an IModBusEvent and must be registered on
		// the mod event bus, not the common gameplay bus.
		modBus.register(new CreativeTabHandler());
	}

	public static List<VariantEntry> entries() {
		return ENTRIES;
	}

	// ------------------------------------------------------------ registration

	/** LOWEST priority: runs after every mod's DeferredRegister has filled the registry. */
	public static class VariantRegistration {
		@SubscribeEvent(priority = EventPriority.LOWEST)
		public void onRegister(RegisterEvent event) {
			if (event.getRegistryKey() == Registries.BLOCK) {
				registerBlocks(event);
			} else if (event.getRegistryKey() == Registries.ITEM) {
				registerItems(event);
			}
		}
	}

	public static class CreativeTabHandler {
		@SubscribeEvent
		public void addContents(BuildCreativeModeTabContentsEvent event) {
			if (event.getTab() != VARIANTS_TAB.get()) {
				return;
			}
			for (VariantEntry entry : ENTRIES) {
				event.accept(new ItemStack(entry.block().asItem()));
			}
		}
	}

	private static void registerBlocks(RegisterEvent event) {
		List<Block> candidates = new ArrayList<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			if (isEligible(block)) {
				candidates.add(block);
			}
		}
		for (Block base : candidates) {
			ResourceLocation baseId = BuiltInRegistries.BLOCK.getKey(base);
			boolean hasSlab = hasVariant(baseId, "slab");
			boolean hasStairs = hasVariant(baseId, "stairs");
			if (!hasSlab) {
				registerBlock(event, base, baseId, VariantKind.SLAB);
			}
			if (!hasStairs) {
				registerBlock(event, base, baseId, VariantKind.STAIRS);
			}
			registerBlock(event, base, baseId, VariantKind.SLOPE);
			registerBlock(event, base, baseId, VariantKind.SLOPE_OUTER);
			registerBlock(event, base, baseId, VariantKind.SLOPE_INNER);
		}
	}

	private static boolean hasVariant(ResourceLocation baseId, String suffix) {
		String ns = baseId.getNamespace();
		String path = baseId.getPath();
		// mod convention: <block>_slab / <block>_stairs
		if (BuiltInRegistries.BLOCK.containsKey(ResourceLocation.fromNamespaceAndPath(ns, path + "_" + suffix))) {
			return true;
		}
		// vanilla naming: oak_planks -> oak_slab / oak_stairs
		if (path.endsWith("_planks")) {
			String stem = path.substring(0, path.length() - "_planks".length());
			return BuiltInRegistries.BLOCK.containsKey(ResourceLocation.fromNamespaceAndPath(ns, stem + "_" + suffix));
		}
		return false;
	}

	private static boolean isEligible(Block block) {
		if (block instanceof SlabBlock || block instanceof StairBlock || block instanceof RoofSlopeBlock) {
			return false;
		}
		if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) {
			return false;
		}
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
		if (id == null || id.equals(BuiltInRegistries.BLOCK.getDefaultKey())) {
			return false;
		}
		// never generate variants from our own generated blocks (vertical slabs, mixed slab, ...)
		if (id.getNamespace().equals(FullSlabs.MODID)) {
			return false;
		}
		// needs a placeable item (fluids, air, spawners, ... have none)
		return block.asItem() != Items.AIR;
	}

	private static void registerBlock(RegisterEvent event, Block base, ResourceLocation baseId, VariantKind kind) {
		ResourceLocation id = FullSlabs.id("variants/" + kind.path() + "/" + baseId.getNamespace() + "/" + baseId.getPath());
		Block block = createBlock(kind, base);
		event.register(Registries.BLOCK, id, () -> block);
		ENTRIES.add(new VariantEntry(id, block, base, kind));
		PENDING_ITEMS.add(new Object[]{id, block, base, kind});
	}

	private static Block createBlock(VariantKind kind, Block base) {
		BlockBehaviour.Properties properties = safeVariantProperties(base);
		return switch (kind) {
			case SLAB -> new SlabBlock(properties);
			case STAIRS -> new StairBlock(base.defaultBlockState(), properties);
			case SLOPE -> new RoofSlopeBlock(Kind.SLOPE, properties);
			case SLOPE_OUTER -> new RoofSlopeBlock(Kind.OUTER, properties);
			case SLOPE_INNER -> new RoofSlopeBlock(Kind.INNER, properties);
		};
	}

	/**
	 * Copies material-facing properties without copying callbacks that are tied to the source
	 * block's state definition. For example, a log's map-color callback reads AXIS; applying that
	 * callback to a generated slab state caused the registry crash because slabs have no AXIS
	 * property. The same issue exists for LIT, SNOWY, WATERLOGGED and other mod-specific
	 * properties. Generated variants use stable values and generic predicates instead.
	 */
	private static BlockBehaviour.Properties safeVariantProperties(Block base) {
		int light = base.defaultBlockState().getLightEmission();
		return BlockBehaviour.Properties.ofFullCopy(base)
				.mapColor(base.defaultMapColor())
				.lightLevel(state -> light)
				.offsetType(BlockBehaviour.OffsetType.NONE)
				.isValidSpawn((state, level, pos, entityType) ->
						state.isFaceSturdy(level, pos, Direction.UP) && state.getLightEmission(level, pos) < 14)
				.isRedstoneConductor((state, level, pos) -> state.isCollisionShapeFullBlock(level, pos))
				.isSuffocating((state, level, pos) -> state.isCollisionShapeFullBlock(level, pos))
				.isViewBlocking((state, level, pos) -> state.isCollisionShapeFullBlock(level, pos))
				.hasPostProcess((state, level, pos) -> false)
				.emissiveRendering((state, level, pos) -> false);
	}

	@SuppressWarnings("unchecked")
	private static void registerItems(RegisterEvent event) {
		for (Object[] pending : PENDING_ITEMS) {
			ResourceLocation id = (ResourceLocation) pending[0];
			Block block = (Block) pending[1];
			Block base = (Block) pending[2];
			VariantKind kind = (VariantKind) pending[3];
			event.register(Registries.ITEM, id, () -> new VariantItem(block, base, kind.suffix(), new Item.Properties()));
		}
	}

	// ------------------------------------------------------------ geometry helpers

	/** World-space element boxes for a generated variant state (render == collision). */
	public static List<Box> boxesFor(VariantKind kind, BlockState state) {
		return switch (kind) {
			case SLAB -> VariantGeometry.slabBoxes(state.getValue(SlabBlock.TYPE).getSerializedName());
			case STAIRS -> VariantGeometry.stairBoxes(
					state.getValue(StairBlock.SHAPE), state.getValue(StairBlock.FACING), state.getValue(StairBlock.HALF));
			case SLOPE -> VariantGeometry.slopeBoxes(Kind.SLOPE, state.getValue(RoofSlopeBlock.FACING));
			case SLOPE_OUTER -> VariantGeometry.slopeBoxes(Kind.OUTER, state.getValue(RoofSlopeBlock.FACING));
			case SLOPE_INNER -> VariantGeometry.slopeBoxes(Kind.INNER, state.getValue(RoofSlopeBlock.FACING));
		};
	}

	/** The state rendered by the item (y=0 / bottom orientation, like vanilla item models). */
	public static BlockState canonicalState(Block block, VariantKind kind) {
		return switch (kind) {
			case SLAB -> block.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
			case STAIRS -> block.defaultBlockState()
					.setValue(StairBlock.FACING, Direction.EAST)
					.setValue(StairBlock.HALF, Half.BOTTOM)
					.setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
			case SLOPE, SLOPE_OUTER, SLOPE_INNER -> block.defaultBlockState().setValue(RoofSlopeBlock.FACING, Direction.EAST);
		};
	}
}
