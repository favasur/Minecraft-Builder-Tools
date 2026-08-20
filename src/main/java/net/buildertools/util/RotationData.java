package net.buildertools.util;

import net.minecraft.world.level.block.state.BlockState;

/**
 * A player-placed rotated block living in the mod's own (neighbor-dependent) block layer. The
 * vanilla cell stays AIR - the block exists only in this layer, carrying its real BlockState so it
 * keeps its shading, breaking, drops and picking while rendering and colliding rotated around its
 * cell center. A {@code billboard} block always faces the player.
 */
public record RotationData(BlockState state, float yaw, float pitch, boolean billboard) {
}
