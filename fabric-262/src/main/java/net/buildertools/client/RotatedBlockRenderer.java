package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.buildertools.server.RotationStore;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import java.util.Map;

/**
 * Draws the mod's rotated blocks with FULL block shading every frame (the PlaceAnywhere
 * technique): instead of mixing into the chunk compiler, each rotated block's model is pre-rotated
 * by {@link RotatedBlockModel} (quads carry world-space face directions) and rendered with
 * continuous per-face shading and per-corner world light via {@link RotatedBlockRendering}, so
 * every face gets the shade its true orientation deserves (exactly vanilla's 1.0/0.8/0.6/0.5 for
 * axis-aligned faces, smooth for tilted ones) and the sky/block light from its real-world
 * neighbors - the block keeps its textures while blending with the surrounding blocks from any
 * viewing angle. The cell itself stays air - this renderer IS the block's visual.
 *
 * <p>Rendered through {@link SubmitCustomGeometryEvent} (fired after the block/entity submission
 * each frame), so the placed block looks exactly like the placement preview (which renders the
 * same way) and the shared {@link NeoForgeRenderBuffer} carries the legacy entity geometry too.
 */
@OnlyIn(Dist.CLIENT)
public final class RotatedBlockRenderer {
    private static final int MAX_RENDER_DIST = 96;

    private RotatedBlockRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        double range = MAX_RENDER_DIST * MAX_RENDER_DIST;

        for (Map.Entry<BlockPos, RotationData> e : RotationStore.clientEntries()) {
            BlockPos pos = e.getKey();
            RotationData rot = e.getValue();
            Vec3 center = rot.center(pos);
            if (camera.distanceToSqr(center) > range) {
                continue;
            }
            BlockState state = rot.state();
            if (state == null || state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            // Billboard blocks always face the player (Hytale), like the placement preview.
            RotatedBlockModel rotatedModel;
            float renderYaw = rot.yaw();
            float renderPitch = rot.pitch();
            if (rot.billboard()) {
                float[] facing = OffGridTransform.billboardAngles(center, camera);
                renderYaw = facing[0];
                renderPitch = facing[1];
                rotatedModel = RotatedBlockModel.build(state, OffGridTransform.rotation(renderYaw, renderPitch));
            } else {
                rotatedModel = RotatedBlockModel.get(state, renderYaw, renderPitch);
            }

            poseStack.pushPose();
            // The model geometry is pre-rotated around its center (see RotatedBlockModel), so the
            // pose only places the local 0..1 model box at the exact model center (fractional for
            // blocks snapped onto a rotated neighbor's grid). The base pose stack already maps
            // world -> camera space, so the center goes in as WORLD coordinates.
            poseStack.translate(center.x - 0.5, center.y - 0.5, center.z - 0.5);
            if (rotatedModel != null) {
                if (!rot.billboard()) {
                    RotatedBlockTriangles.triangles(rot, pos, minecraft.level, renderYaw, renderPitch);
                }
                RotatedBlockRendering.render(rotatedModel, state, pos, center, poseStack,
                        NeoForgeRenderBuffer.shared(), minecraft.level);
            }
            poseStack.popPose();
        }
        NeoForgeRenderBuffer.shared().submit(event);
    }
}
