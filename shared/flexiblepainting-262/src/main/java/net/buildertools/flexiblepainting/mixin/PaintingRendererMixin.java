package net.buildertools.flexiblepainting.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.PaintingRenderer;
import net.minecraft.client.renderer.entity.state.PaintingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PaintingRenderer.class)
public abstract class PaintingRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void flexiblePainting$extract(net.minecraft.world.entity.decoration.painting.Painting painting,
                                           PaintingRenderState state, float partialTicks, CallbackInfo ci) {
        PaintingRenderStateMixin.setSurface(state, painting);
    }

    @Inject(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/PaintingRenderer;renderPainting(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/rendertype/RenderType;[IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"))
    private void flexiblePainting$submit(PaintingRenderState state, PoseStack poseStack,
                                          SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
        SurfaceType type = PaintingRenderStateMixin.surface(state);
        if (type == SurfaceType.FLOOR) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        } else if (type == SurfaceType.CEILING) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
        }
    }

}
