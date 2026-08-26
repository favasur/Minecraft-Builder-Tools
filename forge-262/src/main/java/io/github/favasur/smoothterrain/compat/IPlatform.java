package io.github.favasur.smoothterrain.platform;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public interface IPlatform {
    Identifier getRegistryName(Block block);
    boolean isPlant(BlockState state);
    void updateServerConfigSmoothable(boolean newValue, BlockState... states);
}
