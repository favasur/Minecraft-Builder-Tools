package net.buildertools.flexiblepainting.mixin;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.buildertools.flexiblepainting.api.FlexiblePaintingEntityAccess;
import net.buildertools.flexiblepainting.util.FlexiblePaintingGeometry;
import net.buildertools.flexiblepainting.util.FlexiblePaintingHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntity.class)
public abstract class HangingEntityMixin implements FlexiblePaintingEntityAccess {
    @Shadow
    protected abstract void setDirection(Direction direction);

    @Shadow
    protected abstract void recalculateBoundingBox();

    @Override
    public void flexiblePainting$initialize(BlockPos pos, Direction direction) {
        // BlockAttachedEntity keeps the attachment cell in its inherited protected field. Use
        // its public coordinate setter instead of shadowing that inherited field: Mixin only
        // resolves @Shadow fields declared directly on the target class on NeoForge 1.21.1.
        HangingEntity self = (HangingEntity) (Object) this;
        self.setPos(pos.getX(), pos.getY(), pos.getZ());
        this.setDirection(direction);
    }

    @Override
    public void flexiblePainting$refreshBoundingBox() {
        this.recalculateBoundingBox();
    }

    @Inject(method = "recalculateBoundingBox", at = @At("HEAD"), cancellable = true)
    private void flexiblePainting$recalculate(CallbackInfo ci) {
        HangingEntity self = (HangingEntity) (Object) this;
        if (!(self instanceof Painting painting)) {
            return;
        }
        SurfaceType type = FlexiblePaintingHelper.getSurfaceType(painting);
        if (type == SurfaceType.WALL
                && FlexiblePaintingHelper.getRotationYaw(painting) == 0.0f
                && FlexiblePaintingHelper.getRotationPitch(painting) == 0.0f) {
            return;
        }
        FlexiblePaintingGeometry.applyBoundingBox(painting, type);
        ci.cancel();
    }

    @Inject(method = "survives", at = @At("HEAD"), cancellable = true)
    private void flexiblePainting$survives(CallbackInfoReturnable<Boolean> cir) {
        HangingEntity self = (HangingEntity) (Object) this;
        if (!(self instanceof Painting painting)) {
            return;
        }
        SurfaceType type = FlexiblePaintingHelper.getSurfaceType(painting);
        if (type != SurfaceType.WALL) {
            cir.setReturnValue(FlexiblePaintingGeometry.survives(painting, type));
        }
    }
}
