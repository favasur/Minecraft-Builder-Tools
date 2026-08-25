package net.buildertools.client.settings;

import net.buildertools.common.NoClipState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Client-side mirror of the Creative settings. World/player values (time, weather, flight
 * speed, no clip) are sent to the server when changed; purely visual values (fullbright, selection
 * opacity) and the Entity Tool options (surface lock, grid snap, grid size) live on the client.
 */
@OnlyIn(Dist.CLIENT)
public final class BuilderSettings {
    // Entity Tool
    private static boolean surfaceLock;
    private static boolean gridSnap;
    private static float gridSize = 0.5f;

    // Player
    private static boolean noClip;

    // Brushes (paint / scatter / smooth)
    private static boolean airPlacement;
    private static float airPlaceDistance = 8.0f;
    private static float toolReach = 32.0f;
    private static float brushOpacity = 0.3f;

    // Rendering
    private static float selectionOpacity = 0.15f;
    private static float selectionPanelOpacity = 0.55f;
    private static boolean displayLegend = true;

    // Fullbright (client gamma)
    private static boolean fullbright;
    private static double previousGamma = 0.5;

    private BuilderSettings() {
    }

    public static boolean isSurfaceLock() {
        return surfaceLock;
    }

    public static void setSurfaceLock(boolean value) {
        surfaceLock = value;
    }

    public static boolean isGridSnap() {
        return gridSnap;
    }

    public static void setGridSnap(boolean value) {
        gridSnap = value;
    }

    public static float getGridSize() {
        return gridSize;
    }

    public static void setGridSize(float value) {
        gridSize = value;
    }

    public static float getSelectionOpacity() {
        return selectionOpacity;
    }

    public static void setSelectionOpacity(float value) {
        selectionOpacity = value;
    }

    public static boolean isNoClip() {
        return noClip;
    }

    public static void setNoClip(boolean value) {
        noClip = value;
        // Mirrored into a side-agnostic store so the PlayerMixin (loaded on both sides) can read it.
        NoClipState.setClientEnabled(value);
    }

    public static boolean isAirPlacement() {
        return airPlacement;
    }

    public static void setAirPlacement(boolean value) {
        airPlacement = value;
    }

    public static float getAirPlaceDistance() {
        return airPlaceDistance;
    }

    public static void setAirPlaceDistance(float value) {
        airPlaceDistance = value;
    }

    /** Max reach for the brush tools when clicking. */
    public static float getToolReach() {
        return toolReach;
    }

    public static void setToolReach(float value) {
        toolReach = value;
    }

    public static float getBrushOpacity() {
        return brushOpacity;
    }

    public static void setBrushOpacity(float value) {
        brushOpacity = value;
    }

    public static float getSelectionPanelOpacity() {
        return selectionPanelOpacity;
    }

    public static void setSelectionPanelOpacity(float value) {
        selectionPanelOpacity = value;
    }

    public static boolean isDisplayLegend() {
        return displayLegend;
    }

    public static void setDisplayLegend(boolean value) {
        displayLegend = value;
    }

    public static boolean isFullbright() {
        return fullbright;
    }

    public static void setFullbright(boolean value) {
        if (fullbright == value) {
            return;
        }
        fullbright = value;
        Minecraft minecraft = Minecraft.getInstance();
        Options options = minecraft.options;
        if (value) {
            previousGamma = options.gamma().get();
            options.gamma().set(1.0);
        } else {
            options.gamma().set(previousGamma);
        }
    }

    /** Restores vanilla options when leaving the world (fullbright gamma). */
    public static void reset() {
        if (fullbright) {
            fullbright = false;
            Minecraft.getInstance().options.gamma().set(previousGamma);
        }
    }
}
