package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * Invisible renderer for the solid off-grid block entity. The rotated block model is drawn by the
 * linked {@code BlockDisplay} child; this entity only provides the collision box.
 */
public class OffGridBlockRenderer extends EntityRenderer<OffGridBlockEntity> {
    public OffGridBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(OffGridBlockEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Nothing to draw - the linked block display renders the rotated model.
    }

    @Override
    public ResourceLocation getTextureLocation(OffGridBlockEntity entity) {
        // Never actually bound (shadow radius is 0 and nothing renders); a valid location keeps
        // the texture manager happy if the shadow code asks for it.
        return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png");
    }
}
