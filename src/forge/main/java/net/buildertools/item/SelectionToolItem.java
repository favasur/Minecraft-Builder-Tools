package net.buildertools.item;

import net.minecraft.world.item.Item;

/**
 * The Selection Tool is used to mark a cuboid region in the world.
 *
 * <p>All of its behavior lives in the event handlers (see {@code net.buildertools.client.ClientEvents}
 * for input and {@code net.buildertools.server.BuilderServerHandler} for the server-side operations),
 * so the item itself is intentionally plain.
 */
public class SelectionToolItem extends Item {
    public SelectionToolItem(Properties properties) {
        super(properties);
    }
}
