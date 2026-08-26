package net.buildertools.registry;

import net.buildertools.BuilderToolsMod;
import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BuilderToolsMod.MODID);

    public static final DeferredItem<SelectionToolItem> SELECTION_TOOL = ITEMS.register("selection_tool",
            () -> new SelectionToolItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<EntityToolItem> ENTITY_TOOL = ITEMS.register("entity_tool",
            () -> new EntityToolItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<RulerToolItem> RULER_TOOL = ITEMS.register("ruler_tool",
            () -> new RulerToolItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<LaserToolItem> LASER_TOOL = ITEMS.register("laser_tool",
            () -> new LaserToolItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<ScatterToolItem> SCATTER_TOOL = ITEMS.register("scatter_tool",
            () -> new ScatterToolItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<SmoothToolItem> SMOOTH_TOOL = ITEMS.register("smooth_tool",
            () -> new SmoothToolItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<PaintToolItem> PAINT_TOOL = ITEMS.register("paint_tool",
            () -> new PaintToolItem(new Item.Properties().stacksTo(1)));

    /** Hidden item whose model is the 3D paint brush; it backs the in-hand brush renderer. */
    public static final DeferredItem<Item> BRUSH_PROXY = ITEMS.register("builder_brush",
            () -> new Item(new Item.Properties().stacksTo(1)));
}
