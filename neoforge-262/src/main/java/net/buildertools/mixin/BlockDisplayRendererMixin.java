package net.buildertools.mixin;

import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.BlockDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.phys.AABB;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Air Placement's legacy representation contains a vanilla BlockDisplay child.  The child uses
 * DisplayRenderer's normal block pipeline, which applies axis-quantized model lighting after the
 * display transformation and makes the same rotated face appear to change brightness with the
 * camera.  The paired {@code OffGridBlockEntity} renders the same model through
 * {@code RotatedBlockRendering}, where the normal and light samples are world-space and stable.
 */
@Mixin(DisplayRenderer.BlockDisplayRenderer.class)
public abstract class BlockDisplayRendererMixin {
    @Inject(method = "submitInner", at = @At("HEAD"), cancellable = true)
    private void buildertools$renderLegacyBlockOnlyOnce(
            BlockDisplayEntityRenderState renderState,
            PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector,
            int packedLight,
            float partialTick,
            CallbackInfo ci
    ) {
        // Entity tags are not guaranteed to be present in the client spawn packet; resolve the
        // display entity through the synced display UUID on the paired off-grid block.
        if (isBuilderToolsDisplay(renderState)) {
            ci.cancel();
        }
    }

    private static boolean isBuilderToolsDisplay(BlockDisplayEntityRenderState renderState) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        for (OffGridBlockEntity block : minecraft.level.getEntitiesOfClass(
                OffGridBlockEntity.class, new AABB(
                        renderState.x - 4, renderState.y - 4, renderState.z - 4,
                        renderState.x + 4, renderState.y + 4, renderState.z + 4))) {
            if (block.getDisplayUuid().map(uuid ->
                    nearestDisplay(minecraft, uuid, renderState.x, renderState.y, renderState.z))
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearestDisplay(Minecraft minecraft, java.util.UUID uuid,
                                          double x, double y, double z) {
        for (Display.BlockDisplay display : minecraft.level.getEntitiesOfClass(
                Display.BlockDisplay.class, new AABB(
                        x - 4, y - 4, z - 4, x + 4, y + 4, z + 4))) {
            if (display.getUUID().equals(uuid)) {
                return true;
            }
        }
        return false;
    }
}
