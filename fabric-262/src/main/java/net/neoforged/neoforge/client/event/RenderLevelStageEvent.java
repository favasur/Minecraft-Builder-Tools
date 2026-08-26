package net.neoforged.neoforge.client.event;

import net.minecraft.client.Camera;

public final class RenderLevelStageEvent {
    public enum Stage {
        AFTER_BLOCK_ENTITIES
    }

    private final Stage stage;
    private final Camera camera;

    public RenderLevelStageEvent(Stage stage, Camera camera) {
        this.stage = stage;
        this.camera = camera;
    }

    public Stage getStage() {
        return stage;
    }

    public Camera getCamera() {
        return camera;
    }
}
