package net.buildertools.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Keybinding definitions. Registered on the MOD event bus (RegisterKeyMappingsEvent is an
 * IModBusEvent), separate from {@link ClientEvents} which listens on the game bus.
 */
@OnlyIn(Dist.CLIENT)
public final class KeyBindings {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("buildertools", "buildertools"));

    // Selection tool
    public static final KeyMapping COPY = new KeyMapping("key.buildertools.copy", InputConstants.Type.KEYSYM, InputConstants.KEY_Y, CATEGORY);
    public static final KeyMapping PASTE = new KeyMapping("key.buildertools.paste", InputConstants.Type.KEYSYM, InputConstants.KEY_V, CATEGORY);
    public static final KeyMapping FILL = new KeyMapping("key.buildertools.fill", InputConstants.Type.KEYSYM, InputConstants.KEY_B, CATEGORY);
    public static final KeyMapping UNDO = new KeyMapping("key.buildertools.undo", InputConstants.Type.KEYSYM, InputConstants.KEY_U, CATEGORY);

    // Entity tool
    public static final KeyMapping DELETE = new KeyMapping("key.buildertools.delete", InputConstants.Type.KEYSYM, InputConstants.KEY_X, CATEGORY);
    public static final KeyMapping DUPLICATE = new KeyMapping("key.buildertools.duplicate", InputConstants.Type.KEYSYM, InputConstants.KEY_J, CATEGORY);
    public static final KeyMapping FREEZE = new KeyMapping("key.buildertools.freeze", InputConstants.Type.KEYSYM, InputConstants.KEY_G, CATEGORY);
    public static final KeyMapping ROTATE = new KeyMapping("key.buildertools.rotate", InputConstants.Type.KEYSYM, InputConstants.KEY_R, CATEGORY);
    public static final KeyMapping CONFIRM = new KeyMapping("key.buildertools.confirm", InputConstants.Type.KEYSYM, InputConstants.KEY_RETURN, CATEGORY);
    public static final KeyMapping BILLBOARD = new KeyMapping("key.buildertools.billboard", InputConstants.Type.KEYSYM, InputConstants.KEY_B, CATEGORY);

    private KeyBindings() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(COPY);
        event.register(PASTE);
        event.register(FILL);
        event.register(UNDO);
        event.register(DELETE);
        event.register(DUPLICATE);
        event.register(FREEZE);
        event.register(ROTATE);
        event.register(CONFIRM);
        event.register(BILLBOARD);
    }
}
