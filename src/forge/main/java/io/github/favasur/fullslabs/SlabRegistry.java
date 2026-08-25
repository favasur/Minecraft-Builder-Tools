package io.github.favasur.fullslabs;

import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.OxidizableVerticalSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.handlers.MixedHandlers;
import io.github.favasur.fullslabs.handlers.VanillaMixedHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WeatheringCopperSlabBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegisterEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Forge registry adapter for the shared FullSlabs implementation. */
public final class SlabRegistry {
    public static MixedSlabBlock MIXED_SLAB;
    public static BlockEntityType<MixedSlabBlockEntity> MIXED_SLAB_ENTITY;

    private static boolean initialized;
    private static final List<VerticalSlabBlock> VERTICAL_BLOCKS = new ArrayList<>();

    private SlabRegistry() {
    }

    public static void init(IEventBus modBus) {
        if (initialized) {
            return;
        }
        initialized = true;
        MixedHandlers.register(SlabBlock.class, VanillaMixedHandler.INSTANCE);
        MIXED_SLAB = new MixedSlabBlock(BlockBehaviour.Properties.of());
        modBus.addListener(SlabRegistry::registerEntries);
        modBus.addListener(SlabRegistry::addCreative);
    }

    private static void registerEntries(RegisterEvent event) {
        if (event.getRegistryKey() == Registries.BLOCK) {
            List<Block> slabs = BuiltInRegistries.BLOCK.stream()
                    .filter(SlabBlock.class::isInstance)
                    .toList();
            event.register(Registries.BLOCK, helper -> {
                helper.register(FullSlabs.id("mixed_slab"), MIXED_SLAB);
                for (Block block : slabs) {
                    registerVertical(helper, (SlabBlock) block);
                }
            });
        } else if (event.getRegistryKey() == Registries.BLOCK_ENTITY_TYPE) {
            MIXED_SLAB_ENTITY = new BlockEntityType<>(MixedSlabBlockEntity::new, Set.of(MIXED_SLAB), null);
            event.register(Registries.BLOCK_ENTITY_TYPE,
                    FullSlabs.id("mixed_slab"), () -> MIXED_SLAB_ENTITY);
        } else if (event.getRegistryKey() == Registries.ITEM) {
            event.register(Registries.ITEM, FullSlabs.id("mixed_slab"),
                    () -> new BlockItem(MIXED_SLAB, new Item.Properties()));
        }
    }

    private static void registerVertical(RegisterEvent.RegisterHelper<Block> helper, SlabBlock parent) {
        ResourceLocation parentId = BuiltInRegistries.BLOCK.getKey(parent);
        if (parentId == null || VerticalSlabBlock.MAP_VIEW.containsKey(parent)) {
            return;
        }
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.ofFullCopy(parent).dropsLike(parent);
        VerticalSlabBlock vertical = parent instanceof WeatheringCopperSlabBlock copper
                ? new OxidizableVerticalSlabBlock(copper, properties)
                : new VerticalSlabBlock(parent, properties);
        helper.register(FullSlabs.id(FullSlabs.verticalPath(parentId)), vertical);
        VERTICAL_BLOCKS.add(vertical);
    }

    public static BlockEntityType<MixedSlabBlockEntity> mixedSlabEntity() {
        return MIXED_SLAB_ENTITY;
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS && MIXED_SLAB != null) {
            event.accept(new ItemStack(MIXED_SLAB.asItem()));
        }
    }
}
