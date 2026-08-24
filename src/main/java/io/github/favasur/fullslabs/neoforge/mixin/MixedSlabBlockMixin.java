package io.github.favasur.fullslabs.neoforge.mixin;

import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.ducks.MixedSlabBlockDuck;
import io.github.favasur.fullslabs.util.Utility;
import javax.annotation.ParametersAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@Mixin(value={MixedSlabBlock.class})
public abstract class MixedSlabBlockMixin
extends Block
implements IBlockExtension,
MixedSlabBlockDuck {
    public MixedSlabBlockMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Shadow
    public abstract boolean isSignalSource(BlockGetter var1, BlockPos var2);

    public float getExplosionResistance(BlockState state, BlockGetter world, BlockPos pos, Explosion explosion) {
        return ((Float)this.forwardSidesValue(world, pos, ctx -> Float.valueOf(ctx.mainState().getExplosionResistance(world, pos, explosion)), Math::max)).floatValue();
    }

    public SoundType getSoundType(BlockState state, LevelReader world, BlockPos pos, @Nullable Entity entity) {
        if (!(entity instanceof Player)) {
            return super.getSoundType(state, world, pos, entity);
        }
        Player player = (Player)entity;
        HitResult crosshair = Utility.crosshair(player, world.isClientSide());
        return (SoundType)this.forwardSideValue((BlockGetter)world, pos, crosshair.getLocation(), ctx -> ctx.mainState().getSoundType(world, pos, entity));
    }

    public ItemStack getCloneItemStack(LevelReader world, BlockPos pos, BlockState state, boolean includeData, Player player) {
        HitResult crosshair = Utility.crosshair(player, world.isClientSide());
        return (ItemStack)this.forwardSideValue((BlockGetter)world, pos, crosshair.getLocation(), ctx -> new ItemStack((ItemLike)ctx.mainBlock().asItem()));
    }

    public boolean canConnectRedstone(BlockState state, BlockGetter world, BlockPos pos, @Nullable Direction direction) {
        return this.isSignalSource(world, pos);
    }
}

