package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.RecordEvent;

/**
 * Mod-owned level-submission event for the Forge 65.1.1 render hook. Fired once per frame at the
 * end of {@code LevelRenderer.submitFeatures} (see {@code LevelRendererMixin}) - the same point
 * where NeoForge 26.2 fires its {@code SubmitCustomGeometryEvent} - so the in-world tool overlays
 * and rotated-block geometry are submitted through the level's {@link SubmitNodeCollector} in the
 * same world-space pose. Defined as a record on a typed {@link EventBus}, matching the new
 * Forge event-bus architecture.
 */
public record ForgeLevelRenderEvent(LevelRenderState levelRenderState,
                                    SubmitNodeCollector submitNodeCollector,
                                    PoseStack poseStack) implements RecordEvent {

    public static final EventBus<ForgeLevelRenderEvent> BUS = EventBus.create(ForgeLevelRenderEvent.class);

    public PoseStack getPoseStack() {
        return poseStack;
    }

    public LevelRenderState getLevelRenderState() {
        return levelRenderState;
    }

    public SubmitNodeCollector getSubmitNodeCollector() {
        return submitNodeCollector;
    }
}
