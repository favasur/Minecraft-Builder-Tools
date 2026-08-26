package net.buildertools.client;

import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame render state for the legacy off-grid block entity renderer. */
public class OffGridRenderState extends EntityRenderState {
    public OffGridBlockEntity entity;
}
