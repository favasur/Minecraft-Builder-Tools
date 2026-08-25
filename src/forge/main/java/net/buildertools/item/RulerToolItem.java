package net.buildertools.item;

import net.minecraft.world.item.Item;

/**
 * Measures the distance between two marked points (client-side only; see {@code net.buildertools.selection.RulerState}).
 */
public class RulerToolItem extends Item {
    public RulerToolItem(Properties properties) {
        super(properties);
    }
}
