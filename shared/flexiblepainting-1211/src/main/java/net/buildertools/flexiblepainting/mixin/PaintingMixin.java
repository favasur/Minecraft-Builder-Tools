package net.buildertools.flexiblepainting.mixin;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess;
import net.buildertools.flexiblepainting.api.FlexiblePaintingEntityAccess;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.buildertools.flexiblepainting.util.FlexiblePaintingHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.decoration.Painting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Painting.class)
public abstract class PaintingMixin implements FlexiblePaintingAccess {
    @Inject(method = "defineSynchedData", at = @At("HEAD"))
    private void flexiblePainting$defineData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        FlexiblePaintingHelper.defineSurfaceType(builder);
    }

    @Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
    private void flexiblePainting$refreshData(EntityDataAccessor<?> accessor, CallbackInfo ci) {
        if (FlexiblePaintingHelper.surfaceTypeAccessor().equals(accessor)
                || FlexiblePaintingHelper.rotationYawAccessor().equals(accessor)
                || FlexiblePaintingHelper.rotationPitchAccessor().equals(accessor)) {
            Painting painting = (Painting) (Object) this;
            if (painting instanceof FlexiblePaintingEntityAccess access) {
                access.flexiblePainting$refreshBoundingBox();
            } else {
                painting.refreshDimensions();
            }
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void flexiblePainting$save(CompoundTag tag, CallbackInfo ci) {
        FlexiblePaintingHelper.saveSurfaceType(tag, (Painting) (Object) this);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void flexiblePainting$load(CompoundTag tag, CallbackInfo ci) {
        Painting painting = (Painting) (Object) this;
        FlexiblePaintingHelper.loadSurfaceType(tag, painting);
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

    @Override
    public float flexiblePainting$getRotationYaw() {
        Painting painting = (Painting) (Object) this;
        return painting.getEntityData().get(FlexiblePaintingHelper.rotationYawAccessor());
    }

    @Override
    public float flexiblePainting$getRotationPitch() {
        Painting painting = (Painting) (Object) this;
        return painting.getEntityData().get(FlexiblePaintingHelper.rotationPitchAccessor());
    }

    @Override
    public void flexiblePainting$setRotation(float yaw, float pitch) {
        Painting painting = (Painting) (Object) this;
        painting.getEntityData().set(FlexiblePaintingHelper.rotationYawAccessor(), yaw);
        painting.getEntityData().set(FlexiblePaintingHelper.rotationPitchAccessor(), pitch);
    }
}
