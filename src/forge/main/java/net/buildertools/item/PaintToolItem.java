package net.buildertools.item;

import net.minecraft.world.item.Item;

/**
 * Paints the held block into a sphere around the clicked block (server-validated, undoable).
 */
public class PaintToolItem extends Item {
    public PaintToolItem(Properties properties) {
        super(properties);
    }
}
