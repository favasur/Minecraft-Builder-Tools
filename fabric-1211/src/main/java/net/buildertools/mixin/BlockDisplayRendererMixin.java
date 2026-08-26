package net.buildertools.mixin;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.world.entity.Display;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Air Placement's legacy representation contains a vanilla BlockDisplay child.  The child uses
 * DisplayRenderer's normal block pipeline, which applies axis-quantized model lighting after the
 * display transformation and makes the same rotated face appear to change brightness with the
 * camera.  The paired {@code OffGridBlockEntity} renders the same model through
 * {@code RotatedBlockRendering}, where the normal and light samples are world-space and stable.
 */
@Mixin(DisplayRenderer.BlockDisplayRenderer.class)
public abstract class BlockDisplayRendererMixin {
    @Inject(method = "renderInner", at = @At("HEAD"), cancellable = true)
    private void buildertools$renderLegacyBlockOnlyOnce(
            Display.BlockDisplay display,
            Display.BlockDisplay.BlockRenderState renderState,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            float partialTick,
            CallbackInfo ci
    ) {
        if (display.getTags().contains(BuilderServerHandler.OFF_GRID_TAG)
                || isBuilderToolsDisplay(display)) {
            ci.cancel();
        }
    }

    /** Entity tags are not guaranteed to be present in the client spawn packet; use the synced
     * display UUID on the paired entity as the fallback marker. */
    private static boolean isBuilderToolsDisplay(Display.BlockDisplay display) {
        for (OffGridBlockEntity block : display.level().getEntitiesOfClass(
                OffGridBlockEntity.class, display.getBoundingBox().inflate(2.0))) {
            if (block.getDisplayUuid().map(display.getUUID()::equals).orElse(false)) {
                return true;
            }
        }
        return false;
    }
}
