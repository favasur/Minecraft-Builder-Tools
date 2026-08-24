package net.buildertools.flexiblepainting.mixin;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.buildertools.flexiblepainting.util.FlexiblePaintingHelper;
import net.minecraft.client.renderer.entity.state.PaintingRenderState;
import net.minecraft.world.entity.decoration.painting.Painting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PaintingRenderState.class)
public class PaintingRenderStateMixin {
    @Unique public SurfaceType buildertools$surfaceType = SurfaceType.WALL;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void flexiblePainting$init(CallbackInfo ci) { buildertools$surfaceType = SurfaceType.WALL; }

    public static void setSurface(PaintingRenderState state, Painting painting) {
        ((PaintingRenderStateMixin) (Object) state).buildertools$surfaceType = FlexiblePaintingHelper.getSurfaceType(painting);
    }

    public static SurfaceType surface(PaintingRenderState state) {
        return ((PaintingRenderStateMixin) (Object) state).buildertools$surfaceType;
    }
}
