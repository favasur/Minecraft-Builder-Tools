package net.buildertools.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.buildertools.client.ForgeLevelRenderEvent;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fires {@link ForgeLevelRenderEvent} once per frame at the end of
 * {@code LevelRenderer.submitFeatures}, right after the block outline and before the gizmos are
 * finalized. This is the Forge 65.1.1 equivalent of NeoForge 26.2's {@code SubmitCustomGeometryEvent}:
 * the event carries the level render state, the level's submit collector and the method-local pose
 * stack (identity here, so recorded vertices are in world space - exactly the space the vanilla
 * block outline is drawn in).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(
            method = "submitFeatures",
            at = @At(value = "INVOKE", target = "finalizeGizmoCollection()V", shift = At.Shift.BEFORE)
    )
    private void buildertools$fireSubmitGeometry(LevelRenderState levelRenderState,
                                                 SubmitNodeCollector submitNodeCollector,
                                                 boolean renderOutline,
                                                 CallbackInfo ci) {
        ForgeLevelRenderEvent.BUS.fire(
                new ForgeLevelRenderEvent(levelRenderState, submitNodeCollector, new PoseStack()));
    }
}
