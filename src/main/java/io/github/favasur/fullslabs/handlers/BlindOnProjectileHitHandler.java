package io.github.favasur.fullslabs.handlers;

import io.github.favasur.fullslabs.handlers.MixedHandler;
import io.github.favasur.fullslabs.util.SlabContext;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class BlindOnProjectileHitHandler
implements MixedHandler {
    public static final BlindOnProjectileHitHandler INSTANCE = new BlindOnProjectileHitHandler();

    private BlindOnProjectileHitHandler() {
    }

    @Override
    public void onProjectileHit(SlabContext context, Level world, BlockHitResult hit, Projectile projectile) {
        BlockState state = context.mainState();
        state.onProjectileHit(world, state, hit, projectile);
    }
}

