package io.github.favasur.fullslabs.mixin;

import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.ducks.BlockItemDuck;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value={BlockItem.class})
public class BlockItemMixin
implements BlockItemDuck {
    @Unique
    private BlockState fullslabs$placed;

    @Override
    public BlockState fullslabs$getPlaced() {
        return this.fullslabs$placed;
    }

    @Inject(method={"placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"}, at={@At(value="HEAD")})
    private void skimMixedSlabs(BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!state.is((Block)SlabRegistry.MIXED_SLAB)) {
            return;
        }
        Level world = context.getLevel();
        BlockPos pos = context.getClickedPos();
        ItemStack stack = context.getItemInHand();
        BlockState currentState = world.getBlockState(pos);
        MixedSlabBlock.MixedType type = MixedSlabBlock.MixedType.fromState(currentState);
        SlabBlock currentBlock = VerticalSlabBlock.getRoot(currentState.getBlock());
        boolean towardsCurrent = switch (type) {
            default -> throw new MatchException(null, null);
            case MixedSlabBlock.MixedType.NORTH, MixedSlabBlock.MixedType.SOUTH, MixedSlabBlock.MixedType.EAST, MixedSlabBlock.MixedType.WEST -> {
                if (currentState.getValue(VerticalSlabBlock.TYPE) == VerticalSlabBlock.VerticalType.TOWARDS) {
                    yield true;
                }
                yield false;
            }
            case MixedSlabBlock.MixedType.VERTICAL -> currentState.getValue((Property)BlockStateProperties.SLAB_TYPE) == SlabType.TOP;
        };
        SlabBlock placedBlock = VerticalSlabBlock.getRoot(((BlockItem)stack.getItem()).getBlock());
        this.fullslabs$placed = type.state(placedBlock, !towardsCurrent);
        MixedSlabBlockEntity.writeCache(towardsCurrent ? currentBlock : placedBlock, towardsCurrent ? placedBlock : currentBlock);
    }
}

