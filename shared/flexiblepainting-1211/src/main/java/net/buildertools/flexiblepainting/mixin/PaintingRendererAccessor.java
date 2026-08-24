package net.buildertools.flexiblepainting.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.PaintingRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.entity.decoration.Painting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PaintingRenderer.class)
public interface PaintingRendererAccessor {
    @Invoker("renderPainting")
    void flexiblePainting$renderPainting(PoseStack poseStack, VertexConsumer consumer, Painting painting,
                                          int width, int height, TextureAtlasSprite front, TextureAtlasSprite back);
}
