package io.github.favasur.fullslabs.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value={LevelChunk.class})
public class LevelChunkMixin {
    @ModifyVariable(method={"setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"}, at=@At(value="MIXINEXTRAS:EXPRESSION", shift=At.Shift.AFTER))
    @Definition(id="newBlockEntity", method={"Lnet/minecraft/world/level/block/EntityBlock;newBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/entity/BlockEntity;"})
    @Expression(value={"? = ?.newBlockEntity(?, ?)"})
    private BlockEntity changeMixedSlabEntity(BlockEntity entity) {
        if (!(entity instanceof MixedSlabBlockEntity)) {
            return entity;
        }
        MixedSlabBlockEntity mixed = (MixedSlabBlockEntity)entity;
        mixed.readCache();
        return mixed;
    }
}

