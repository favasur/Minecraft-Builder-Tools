package io.github.favasur.smoothterrain.fabric;

import io.github.favasur.smoothterrain.config.SmoothTerrainConfig;
import io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl;
import io.github.favasur.smoothterrain.platform.IClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Consumer;

public final class ClientPlatform implements IClientPlatform {
    @Override
    public void updateClientVisuals(boolean render) {
        SmoothTerrainConfig.Client.render = render;
    }

    @Override
    public void sendC2SRequestUpdateSmoothable(boolean newValue, BlockState[] states) {
    }

    @Override
    public void loadDefaultServerConfig() {
        SmoothTerrainConfigImpl.Server.setEnabled(false);
    }

    @Override
    public void receiveSyncedServerConfig(byte[] configData) {
    }

    @Override
    public Component clientConfigComponent() {
        return Component.literal("Fabric config");
    }

    @Override
    public void forEachRenderLayer(BlockState state, Consumer<RenderType> action) {
        action.accept(ItemBlockRenderTypes.getChunkRenderType(state));
    }

    @Override
    public List<BakedQuad> getQuads(BakedModel model, BlockState state, Direction direction,
                                    RandomSource random, Object modelData, RenderType layer) {
        return model.getQuads(state, direction, random);
    }
}
