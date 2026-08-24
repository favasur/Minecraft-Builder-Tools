package net.buildertools.flexiblepainting.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.buildertools.flexiblepainting.util.FlexiblePaintingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.PaintingRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariant;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PaintingRenderer.class)
public abstract class PaintingRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void flexiblePainting$render(Painting painting, float entityYaw, float partialTicks,
                                          PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                                          CallbackInfo ci) {
        SurfaceType type = FlexiblePaintingHelper.getSurfaceType(painting);
        float customYaw = FlexiblePaintingHelper.getRotationYaw(painting);
        float customPitch = FlexiblePaintingHelper.getRotationPitch(painting);
        if (type == SurfaceType.WALL && customYaw == 0.0f && customPitch == 0.0f) {
            return;
        }

        poseStack.pushPose();
        if (type == SurfaceType.WALL) {
            // PaintingRenderer normally derives this from entityYaw. Builder Tools keeps the
            // vanilla facing in Painting#direction and stores the user's extra yaw separately,
            // so a wall painting can be rotated without changing its attachment direction.
            float baseYaw = painting.getDirection().get2DDataValue() * 90.0f;
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0f - baseYaw));
        } else {
            flexiblePainting$transform(poseStack, painting.getDirection(), type);
        }
        if (customYaw != 0.0f || customPitch != 0.0f) {
            poseStack.mulPose(Axis.YP.rotationDegrees(-customYaw));
            poseStack.mulPose(Axis.XP.rotationDegrees(customPitch));
        }
        PaintingVariant variant = painting.getVariant().value();
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlasSprite back = minecraft.getPaintingTextures().getBackSprite();
        TextureAtlasSprite front = minecraft.getPaintingTextures().get(variant);
        VertexConsumer consumer = buffers.getBuffer(RenderType.entitySolid(back.atlasLocation()));
        ((PaintingRendererAccessor) this).flexiblePainting$renderPainting(
                poseStack, consumer, painting, variant.width(), variant.height(), front, back);
        poseStack.popPose();
        ci.cancel();
    }

    private static void flexiblePainting$transform(PoseStack poseStack, Direction direction, SurfaceType type) {
        if (type == SurfaceType.FLOOR) {
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
            poseStack.mulPose(Axis.YP.rotationDegrees(flexiblePainting$floorYaw(direction)));
        } else {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0f));
            poseStack.mulPose(Axis.YP.rotationDegrees(flexiblePainting$ceilingYaw(direction)));
        }
    }

    private static float flexiblePainting$floorYaw(Direction direction) {
        return switch (direction) {
            case NORTH -> 0.0f;
            case SOUTH -> 180.0f;
            case WEST -> 90.0f;
            case EAST -> -90.0f;
            default -> 0.0f;
        };
    }

    private static float flexiblePainting$ceilingYaw(Direction direction) {
        return switch (direction) {
            case NORTH -> 0.0f;
            case SOUTH -> 180.0f;
            case WEST -> -90.0f;
            case EAST -> 90.0f;
            default -> 0.0f;
        };
    }
}
