package net.buildertools.item;

import net.minecraft.world.item.Item;

/**
 * Scatters the held block at random spots on surfaces within a sphere (server-validated, undoable).
 */
public class ScatterToolItem extends Item {
    public ScatterToolItem(Properties properties) {
        super(properties);
    }
}
