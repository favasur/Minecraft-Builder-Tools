package io.github.favasur.smoothterrain.compat;

import io.github.favasur.smoothterrain.config.SmoothTerrainConfig;
import io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The Video Settings "Smooth Terrain" toggle. It drives the same enable/disable path as the
 * Creative Settings world toggle ({@link SmoothTerrainConfigImpl.Server#setEnabled(boolean)})
 * and refreshes the rendering afterwards so the change is visible immediately.
 */
public final class SmoothTerrainToggleButton {

    private SmoothTerrainToggleButton() {
    }

    public static Button create() {
        return Button.builder(label(), button -> {
            boolean next = !isEnabled();
            SmoothTerrainConfigImpl.Server.setEnabled(next);
            SmoothTerrainConfigImpl.refreshRendering();
            button.setMessage(label(next));
        }).width(150).build();
    }

    public static boolean isEnabled() {
        return SmoothTerrainConfig.Client.render;
    }

    public static Component label() {
        return label(isEnabled());
    }

    private static Component label(boolean enabled) {
        return Component.literal("Smooth Terrain: " + (enabled ? "ON" : "OFF"));
    }
}
