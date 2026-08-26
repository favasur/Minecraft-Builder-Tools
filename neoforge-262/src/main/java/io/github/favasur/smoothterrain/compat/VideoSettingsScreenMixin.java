package io.github.favasur.smoothterrain.compat;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the Smooth Terrain toggle button and roughness slider as the last row of the Video
 * Settings options list. {@code addOptions} is invoked by {@code OptionsSubScreen#addContents}
 * after the list is created, and the list is rebuilt on every {@code init}, so the injection is
 * safe across window resizes.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {

    @Shadow
    protected OptionsList list;

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void smoothterrain$addOptions(CallbackInfo ci) {
        Button toggle = SmoothTerrainToggleButton.create();
        AbstractSliderButton slider = SmoothTerrainRoughnessSlider.create();
        this.list.addSmall(toggle, slider);
    }
}
