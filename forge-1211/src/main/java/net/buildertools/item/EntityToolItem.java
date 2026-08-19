package net.buildertools.item;

import net.minecraft.world.item.Item;

/**
 * The Entity Tool is used to select, move, rotate, duplicate and delete entities.
 *
 * <p>All of its behavior lives in the event handlers (see {@code net.buildertools.client.ClientEvents}
 * for input and {@code net.buildertools.server.BuilderServerHandler} for the server-side operations),
 * so the item itself is intentionally plain.
 */
public class EntityToolItem extends Item {
    public EntityToolItem(Properties properties) {
        super(properties);
    }
}
