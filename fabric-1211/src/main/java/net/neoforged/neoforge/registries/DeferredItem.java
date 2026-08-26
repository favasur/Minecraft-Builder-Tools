package net.neoforged.neoforge.registries;

import net.minecraft.world.item.Item;

public final class DeferredItem<T extends Item> extends DeferredHolder<Item, T> {
    public DeferredItem(T value) {
        super(value);
    }
}
