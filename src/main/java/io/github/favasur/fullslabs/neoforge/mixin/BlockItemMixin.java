package io.github.favasur.fullslabs.neoforge.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.ducks.BlockItemDuck;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value={BlockItem.class})
public class BlockItemMixin {
    @ModifyArg(method={"place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;"}, at=@At(value="INVOKE", target="Lnet/minecraft/world/item/BlockItem;getPlaceSound(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/sounds/SoundEvent;"))
    private BlockState mixedSlabPlacementSounds(BlockState state, @Local(argsOnly=true) BlockPlaceContext context) {
        if (!state.is((Block)SlabRegistry.MIXED_SLAB)) {
            return state;
        }
        BlockItemMixin blockItemMixin = this;
        if (blockItemMixin instanceof BlockItemDuck) {
            BlockItemDuck self = (BlockItemDuck)((Object)blockItemMixin);
            return self.fullslabs$getPlaced() == null ? state : self.fullslabs$getPlaced();
        }
        throw new AssertionError();
    }
}

