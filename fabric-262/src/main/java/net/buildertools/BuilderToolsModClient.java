package net.buildertools;

import io.github.favasur.smoothterrain.fabric.ClientInit;
import net.buildertools.client.ClientEvents;
import net.buildertools.client.RotatedBlockRenderer;
import net.buildertools.client.SelectionRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client entrypoint: runs after {@link BuilderToolsMod#onInitialize()}, so the client-side
 * handlers are registered on the game bus here and only then are the client Fabric hooks
 * wired (so no event is fired before its listeners exist).
 */
public final class BuilderToolsModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientInit.register();
        NeoForge.EVENT_BUS.register(ClientEvents.class);
        NeoForge.EVENT_BUS.register(SelectionRenderer.class);
        NeoForge.EVENT_BUS.register(RotatedBlockRenderer.class);
        FabricHooks.registerClient();
    }
}
