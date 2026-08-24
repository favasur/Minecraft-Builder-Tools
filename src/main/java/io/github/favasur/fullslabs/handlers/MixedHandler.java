package io.github.favasur.fullslabs.handlers;

import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.util.SlabContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public interface MixedHandler {
    default public MixedSlabBlock mixed() {
        return SlabRegistry.MIXED_SLAB;
    }

    default public void randomTick(SlabContext context, ServerLevel world, BlockPos pos, RandomSource random) {
    }

    default public boolean isSignalSource(SlabContext context) {
        return false;
    }

    default public int getDirectSignal(SlabContext context, BlockGetter world, BlockPos pos, Direction direction) {
        return 0;
    }

    default public int getSignal(SlabContext context, BlockGetter world, BlockPos pos, Direction direction) {
        return 0;
    }

    default public void onProjectileHit(SlabContext context, Level world, BlockHitResult hit, Projectile projectile) {
    }

    default public void stepOn(SlabContext context, Level world, BlockPos pos, Entity entity) {
    }

    default public void fallOn(SlabContext context, Level world, BlockPos pos, Entity entity, double fallDistance) {
        entity.causeFallDamage((float) fallDistance, 1.0f, entity.damageSources().fall());
    }

    default public void updateEntityMovementAfterFallOn(SlabContext context, BlockGetter world, Entity entity) {
        entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0, 0.0, 1.0));
    }

    default public void handlePrecipitation(SlabContext context, Level world, BlockPos pos, Biome.Precipitation precipitation) {
    }

    default public void attack(SlabContext context, Level world, BlockPos pos, Player player) {
    }

    default public void playerDestroy(SlabContext context, Level world, Player player, BlockPos pos, @Nullable BlockEntity blockEntity, ItemStack tool) {
    }

    default public void tick(SlabContext context, ServerLevel world, BlockPos pos, RandomSource random) {
    }

    default public void spawnAfterBreak(SlabContext context, ServerLevel world, BlockPos pos, ItemStack tool, boolean dropExperience) {
    }

    default public InteractionResult useWithoutItem(SlabContext context, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    default public ItemInteractionResult useItemOn(SlabContext context, ItemStack stack, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}

