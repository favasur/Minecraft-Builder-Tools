package io.github.favasur.fullslabs.util;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Small shared helpers for the 26.2 FullSlabs graft. */
public final class Utility {

    private Utility() {
    }

    public static HitResult crosshair(Player player) {
        return player.pick(player.blockInteractionRange(), 1.0F, false);
    }

    /**
     * Splits a horizontal double slab into the half the player is looking at and the remaining
     * half. Returns {@code null} for anything that is not a double slab.
     */
    public static StatePair breakHalf(BlockGetter view, BlockState state, BlockPos pos, HitResult crosshair) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(state);
        Objects.requireNonNull(pos);
        Objects.requireNonNull(crosshair);
        if (!(state.getBlock() instanceof SlabBlock)
                || state.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE) {
            return null;
        }
        Vec3 hit = crosshair.getLocation();
        boolean towards = hit.y - (double) pos.getY() >= 0.5;
        return new StatePair(
                state.setValue(BlockStateProperties.SLAB_TYPE, towards ? SlabType.TOP : SlabType.BOTTOM),
                state.setValue(BlockStateProperties.SLAB_TYPE, towards ? SlabType.BOTTOM : SlabType.TOP));
    }

    public record StatePair(BlockState towards, BlockState away) {
    }
}
