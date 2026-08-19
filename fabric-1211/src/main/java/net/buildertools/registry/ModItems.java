package net.buildertools.registry;

import net.buildertools.BuilderToolsMod;
import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public class ModItems {
    public static final Item SELECTION_TOOL = register("selection_tool",
            new SelectionToolItem(new Item.Properties().stacksTo(1)));
    public static final Item ENTITY_TOOL = register("entity_tool",
            new EntityToolItem(new Item.Properties().stacksTo(1)));
    public static final Item RULER_TOOL = register("ruler_tool",
            new RulerToolItem(new Item.Properties().stacksTo(1)));
    public static final Item LASER_TOOL = register("laser_tool",
            new LaserToolItem(new Item.Properties().stacksTo(1)));
    public static final Item SCATTER_TOOL = register("scatter_tool",
            new ScatterToolItem(new Item.Properties().stacksTo(1)));
    public static final Item SMOOTH_TOOL = register("smooth_tool",
            new SmoothToolItem(new Item.Properties().stacksTo(1)));
    public static final Item PAINT_TOOL = register("paint_tool",
            new PaintToolItem(new Item.Properties().stacksTo(1)));

    /** Hidden item whose model is the 3D paint brush; it backs the in-hand brush renderer. */
    public static final Item BRUSH_PROXY = register("builder_brush",
            new Item(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }

    private static Item register(String name, Item item) {
        return Registry.register(BuiltInRegistries.ITEM,
                ResourceLocation.fromNamespaceAndPath(BuilderToolsMod.MODID, name), item);
    }
}
