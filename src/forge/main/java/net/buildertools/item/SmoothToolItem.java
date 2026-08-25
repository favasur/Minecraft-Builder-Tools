package net.buildertools.item;

import net.minecraft.world.item.Item;

/**
 * Averages terrain heights across a disc around the clicked block (server-validated, undoable).
 */
public class SmoothToolItem extends Item {
    public SmoothToolItem(Properties properties) {
        super(properties);
    }
}
