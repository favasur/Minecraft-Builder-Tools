package net.buildertools.client;

import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/**
 * Invisible renderer for the solid off-grid block entity. The rotated block model is drawn by the
 * linked {@code BlockDisplay} child; this entity only provides the collision box.
 */
public class OffGridBlockRenderer extends EntityRenderer<OffGridBlockEntity, EntityRenderState> {
    public OffGridBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }
}
