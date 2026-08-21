package net.buildertools;

import net.buildertools.client.ClientEvents;
import net.buildertools.client.KeyBindings;
import net.buildertools.client.OffGridBlockRenderer;
import net.buildertools.client.RotatedBlockRenderer;
import net.buildertools.client.SelectionRenderer;
import net.buildertools.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class BuilderToolsModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // KeyBindings registers itself through KeyBindingHelper on class init; touch it so the
        // key mappings exist before the first frame.
        KeyBindings.class.getName();
        EntityRendererRegistry.register(ModEntities.OFF_GRID_BLOCK, OffGridBlockRenderer::new);
        ClientEvents.register();
        SelectionRenderer.register();
        RotatedBlockRenderer.register();
    }
}
