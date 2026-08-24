package io.github.favasur.fullslabs.config.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.favasur.fullslabs.config.Controls;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

public final class ControlsImpl {

	private ControlsImpl() {
	}

	public static void register(RegisterKeyMappingsEvent event) {
		Controls.MAIN = "fullslabs";
		Controls.toggleOverlay = new KeyMapping("key.fullslabs.toggle_overlay", InputConstants.UNKNOWN.getValue(), Controls.MAIN);
		Controls.cycleMode = new KeyMapping("key.fullslabs.cycle_mode", 86, Controls.MAIN);
		event.register(Controls.toggleOverlay);
		event.register(Controls.cycleMode);
	}
}
