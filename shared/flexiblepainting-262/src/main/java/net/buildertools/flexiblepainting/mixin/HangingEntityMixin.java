package net.buildertools.flexiblepainting.mixin;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.buildertools.flexiblepainting.util.FlexiblePaintingGeometry;
import net.buildertools.flexiblepainting.util.FlexiblePaintingHelper;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.painting.Painting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin {
    @Inject(method = "recalculateBoundingBox", at = @At("HEAD"), cancellable = true)
    private void flexiblePainting$recalculate(CallbackInfo ci) {
        HangingEntity self = (HangingEntity) (Object) this;
        if (self instanceof Painting painting) {
            SurfaceType type = FlexiblePaintingHelper.getSurfaceType(painting);
            if (type != SurfaceType.WALL) {
                FlexiblePaintingGeometry.applyBoundingBox(painting, type);
                ci.cancel();
            }
        }
    }

    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void flexiblePainting$survives(CallbackInfoReturnable<Boolean> cir) {
        HangingEntity self = (HangingEntity) (Object) this;
        if (self instanceof Painting painting) {
            SurfaceType type = FlexiblePaintingHelper.getSurfaceType(painting);
            if (type != SurfaceType.WALL) cir.setReturnValue(FlexiblePaintingGeometry.survives(painting, type));
        }
    }
}
