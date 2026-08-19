package net.buildertools.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.buildertools.BuilderToolsMod;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Keybinding definitions. 26.2 groups keybindings by {@link KeyMapping.Category} (a registered
 * {@link Identifier}); Fabric registers the mappings through KeyMappingHelper.
 */
public final class KeyBindings {
    /** 26.2 groups keybindings by {@link KeyMapping.Category} instead of a lang key. */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BuilderToolsMod.MODID, "category"));

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
        KeyMappingHelper.registerKeyMapping(mapping);
        return mapping;
    }
}
