package io.github.favasur.fullslabs;

import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.OxidizableVerticalSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.handlers.MixedHandlers;
import io.github.favasur.fullslabs.handlers.VanillaMixedHandler;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WeatheringCopperSlabBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Fabric registry adapter for the shared FullSlabs implementation. */
public final class SlabRegistry {
    public static MixedSlabBlock MIXED_SLAB;
    public static BlockEntityType<MixedSlabBlockEntity> MIXED_SLAB_ENTITY;

    private static boolean initialized;
    private static final List<VerticalSlabBlock> VERTICAL_BLOCKS = new ArrayList<>();

    private SlabRegistry() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        MixedHandlers.register(SlabBlock.class, VanillaMixedHandler.INSTANCE);

        MIXED_SLAB = Registry.register(BuiltInRegistries.BLOCK,
                FullSlabs.id("mixed_slab"),
                new MixedSlabBlock(BlockBehaviour.Properties.of()));
        Registry.register(BuiltInRegistries.ITEM,
                FullSlabs.id("mixed_slab"),
                new BlockItem(MIXED_SLAB, new Item.Properties()));
        MIXED_SLAB_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                FullSlabs.id("mixed_slab"),
                new BlockEntityType<>(MixedSlabBlockEntity::new, Set.of(MIXED_SLAB), null));

        List<Block> slabs = BuiltInRegistries.BLOCK.stream()
                .filter(SlabBlock.class::isInstance)
                .filter(block -> block != MIXED_SLAB)
                .toList();
        for (Block block : slabs) {
            registerVertical((SlabBlock) block);
        }

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS)
                .register(entries -> entries.accept(MIXED_SLAB.asItem()));
        FullSlabs.LOGGER.info("Registered {} vertical slabs", VERTICAL_BLOCKS.size());
    }

    private static void registerVertical(SlabBlock parent) {
        ResourceLocation parentId = BuiltInRegistries.BLOCK.getKey(parent);
        if (parentId == null || VerticalSlabBlock.MAP_VIEW.containsKey(parent)) {
            return;
        }
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.ofFullCopy(parent).dropsLike(parent);
        VerticalSlabBlock vertical = parent instanceof WeatheringCopperSlabBlock copper
                ? new OxidizableVerticalSlabBlock(copper, properties)
                : new VerticalSlabBlock(parent, properties);
        Registry.register(BuiltInRegistries.BLOCK, FullSlabs.id(FullSlabs.verticalPath(parentId)), vertical);
        VERTICAL_BLOCKS.add(vertical);
    }

    public static MixedSlabBlock mixedSlab() {
        return MIXED_SLAB;
    }

    public static BlockEntityType<MixedSlabBlockEntity> mixedSlabEntity() {
        return MIXED_SLAB_ENTITY;
    }
}
