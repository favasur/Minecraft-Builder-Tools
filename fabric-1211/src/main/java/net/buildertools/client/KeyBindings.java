package net.buildertools.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Keybinding definitions. Fabric registers these through KeyBindingHelper instead of an event.
 */
public final class KeyBindings {
    public static final String CATEGORY = "key.categories.buildertools";

    // Selection tool
    public static final KeyMapping COPY = register(new KeyMapping("key.buildertools.copy", InputConstants.Type.KEYSYM, InputConstants.KEY_Y, CATEGORY));
    public static final KeyMapping PASTE = register(new KeyMapping("key.buildertools.paste", InputConstants.Type.KEYSYM, InputConstants.KEY_V, CATEGORY));
    public static final KeyMapping FILL = register(new KeyMapping("key.buildertools.fill", InputConstants.Type.KEYSYM, InputConstants.KEY_B, CATEGORY));
    public static final KeyMapping UNDO = register(new KeyMapping("key.buildertools.undo", InputConstants.Type.KEYSYM, InputConstants.KEY_U, CATEGORY));

    // Entity tool
    public static final KeyMapping DELETE = register(new KeyMapping("key.buildertools.delete", InputConstants.Type.KEYSYM, InputConstants.KEY_X, CATEGORY));
    public static final KeyMapping DUPLICATE = register(new KeyMapping("key.buildertools.duplicate", InputConstants.Type.KEYSYM, InputConstants.KEY_J, CATEGORY));
    public static final KeyMapping FREEZE = register(new KeyMapping("key.buildertools.freeze", InputConstants.Type.KEYSYM, InputConstants.KEY_G, CATEGORY));
    public static final KeyMapping ROTATE = register(new KeyMapping("key.buildertools.rotate", InputConstants.Type.KEYSYM, InputConstants.KEY_R, CATEGORY));
    public static final KeyMapping CONFIRM = register(new KeyMapping("key.buildertools.confirm", InputConstants.Type.KEYSYM, InputConstants.KEY_RETURN, CATEGORY));

    private KeyBindings() {
    }

    private static KeyMapping register(KeyMapping mapping) {
        KeyBindingHelper.registerKeyBinding(mapping);
        return mapping;
    }
}
