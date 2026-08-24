package net.buildertools.flexiblepainting.mixin;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.buildertools.flexiblepainting.util.FlexiblePaintingHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Painting.class)
public abstract class PaintingMixin implements FlexiblePaintingAccess {
    @Inject(method = "defineSynchedData", at = @At("HEAD"))
    private void flexiblePainting$defineData(SynchedEntityData.Builder builder, CallbackInfo ci) { FlexiblePaintingHelper.defineSurfaceType(builder); }

    @Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
    private void flexiblePainting$refreshData(EntityDataAccessor<?> accessor, CallbackInfo ci) {
        if (FlexiblePaintingHelper.surfaceTypeAccessor().equals(accessor)) ((Painting) (Object) this).refreshDimensions();
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void flexiblePainting$save(ValueOutput output, CallbackInfo ci) { FlexiblePaintingHelper.saveSurfaceType(output, (Painting) (Object) this); }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void flexiblePainting$load(ValueInput input, CallbackInfo ci) {
        Painting painting = (Painting) (Object) this;
        FlexiblePaintingHelper.loadSurfaceType(input, painting);
        painting.refreshDimensions();
    }

    @Override
    public SurfaceType flexiblePainting$getSurfaceType() {
        Painting painting = (Painting) (Object) this;
        return SurfaceType.fromId(painting.getEntityData().get(FlexiblePaintingHelper.surfaceTypeAccessor()));
    }

    @Override
    public void flexiblePainting$setSurfaceType(SurfaceType type) {
        Painting painting = (Painting) (Object) this;
        painting.getEntityData().set(FlexiblePaintingHelper.surfaceTypeAccessor(), type.id());
    }
}
