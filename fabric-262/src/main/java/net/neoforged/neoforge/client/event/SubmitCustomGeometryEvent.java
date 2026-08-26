package net.neoforged.neoforge.client.event;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Fabric shim for the NeoForge 26.2 level-submission event. The copied renderer entrypoints take
 * this event so they stay canonical; on Fabric nothing fires it, so the in-world tool overlays are
 * recorded only (parity with the Fabric 1.21.1 bridge).
 */
public final class SubmitCustomGeometryEvent {
    private final PoseStack poseStack;
    private final SubmitNodeCollector submitNodeCollector;
    private final LevelRenderState levelRenderState;

    public SubmitCustomGeometryEvent(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                     LevelRenderState levelRenderState) {
        this.poseStack = poseStack;
        this.submitNodeCollector = submitNodeCollector;
        this.levelRenderState = levelRenderState;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }

    public SubmitNodeCollector getSubmitNodeCollector() {
        return submitNodeCollector;
    }

    public LevelRenderState getLevelRenderState() {
        return levelRenderState;
    }
}
