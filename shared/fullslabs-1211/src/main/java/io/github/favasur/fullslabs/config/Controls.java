package io.github.favasur.fullslabs.config;

import io.github.favasur.fullslabs.util.SlabPlacement;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Minimal shared control state; loader entrypoints may add key mappings around it. */
public final class Controls {
    private static final Map<UUID, SlabPlacement.Mode> MODES = new HashMap<>();
    private static boolean overlayActive = true;

    private Controls() {
    }

    public static SlabPlacement.Mode getPlacementMode(UUID player) {
        return MODES.computeIfAbsent(player, ignored -> SlabPlacement.Mode.HYBRID);
    }

    public static boolean isOverlayActive() {
        return overlayActive;
    }

    public static void toggleOverlayActive() {
        overlayActive = !overlayActive;
    }

    /** Hook retained for loader adapters that provide a network channel. */
    public static void onClientTick(Minecraft client) {
    }
}
