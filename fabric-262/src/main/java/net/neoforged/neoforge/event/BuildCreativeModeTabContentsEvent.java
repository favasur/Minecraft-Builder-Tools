package net.neoforged.neoforge.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

import java.util.function.Consumer;

/**
 * Fired on the mod bus when the tools &amp; utilities creative tab is built, with a registrar
 * backed by Fabric's {@code CreativeModeTabEvents} so {@code accept} actually adds the items.
 */
public final class BuildCreativeModeTabContentsEvent {
    private final ResourceKey<CreativeModeTab> tabKey;
    private final Consumer<ItemStack> registrar;

    public BuildCreativeModeTabContentsEvent(ResourceKey<CreativeModeTab> tabKey, Consumer<ItemStack> registrar) {
        this.tabKey = tabKey;
        this.registrar = registrar;
    }

    public ResourceKey<CreativeModeTab> getTabKey() {
        return tabKey;
    }

    public void accept(ItemLike item) {
        registrar.accept(new ItemStack(item));
    }

    public void accept(ItemStack stack) {
        registrar.accept(stack);
    }
}
