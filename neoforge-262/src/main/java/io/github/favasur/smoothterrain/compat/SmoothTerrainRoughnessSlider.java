package io.github.favasur.smoothterrain.compat;

import io.github.favasur.smoothterrain.config.SmoothTerrainConfig;
import io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl;
import java.util.Locale;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The Video Settings "Terrain Smoothness" slider: 1 is perfectly smooth, 0 is maximally rough.
 * The value is written to the shared {@link SmoothTerrainConfig.Server#oldSmoothTerrainRoughness}
 * setting (stored inverted) live while dragging, and all chunks are re-rendered once the mouse
 * is released so the change is visible immediately.
 */
public final class SmoothTerrainRoughnessSlider {

    private SmoothTerrainRoughnessSlider() {
    }

    public static AbstractSliderButton create() {
        return new AbstractSliderButton(
                0, 0, 150, 20, Component.literal("Terrain Smoothness"),
                Mth.clamp(1.0 - SmoothTerrainConfig.Server.oldSmoothTerrainRoughness, 0.0, 1.0)) {

            @Override
            protected void updateMessage() {
                this.setMessage(Component.literal(String.format(
                        Locale.ROOT, "Terrain Smoothness: %.2f",
                        1.0 - SmoothTerrainConfig.Server.oldSmoothTerrainRoughness)));
            }

            @Override
            protected void applyValue() {
                SmoothTerrainConfigImpl.Server.setSmoothness((float) this.value);
                this.updateMessage();
            }

            @Override
            public void onRelease(MouseButtonEvent event) {
                super.onRelease(event);
                SmoothTerrainConfigImpl.refreshRendering();
            }
        };
    }
}
