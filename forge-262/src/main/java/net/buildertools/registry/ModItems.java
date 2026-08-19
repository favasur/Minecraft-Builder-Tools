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
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BuilderToolsMod.MODID);

    public static final RegistryObject<SelectionToolItem> SELECTION_TOOL = ITEMS.register("selection_tool",
            () -> new SelectionToolItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<EntityToolItem> ENTITY_TOOL = ITEMS.register("entity_tool",
            () -> new EntityToolItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<RulerToolItem> RULER_TOOL = ITEMS.register("ruler_tool",
            () -> new RulerToolItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<LaserToolItem> LASER_TOOL = ITEMS.register("laser_tool",
            () -> new LaserToolItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<ScatterToolItem> SCATTER_TOOL = ITEMS.register("scatter_tool",
            () -> new ScatterToolItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<SmoothToolItem> SMOOTH_TOOL = ITEMS.register("smooth_tool",
            () -> new SmoothToolItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<PaintToolItem> PAINT_TOOL = ITEMS.register("paint_tool",
            () -> new PaintToolItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }
}
