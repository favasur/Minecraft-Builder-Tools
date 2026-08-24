package io.github.favasur.fullslabs.neoforge.mixin;

import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.neoforge.FullSlabsNeoForge;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@MethodsReturnNonnullByDefault
@Mixin(MixedSlabBlockEntity.class)
public abstract class MixedSlabBlockEntityMixin extends BlockEntity {
    @Shadow
    public abstract BlockState getTowardsState();

    @Shadow
    public abstract BlockState getAwayState();

    public MixedSlabBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public ModelData getModelData() {
        BlockState towardsState = this.getTowardsState();
        BlockState awayState = this.getAwayState();
        MixedSlabBlockEntity.ModelContext context = MixedSlabBlockEntity.ModelContext.fromStates(towardsState, awayState);
        return ModelData.of(FullSlabsNeoForge.MIXED_CONTEXT_MODEL_PROPERTY, context);
    }

    @Inject(method = "loadAdditional(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", at = @At("TAIL"))
    private void updateModelAfterRead(CompoundTag tag, HolderLookup.Provider registries, CallbackInfo ci) {
        this.requestModelDataUpdate();
        if (this.level == null) {
            return;
        }
        BlockState state = this.getBlockState();
        this.level.sendBlockUpdated(this.worldPosition, state, state, 19);
    }
}
