package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.buildertools.server.RotationStore;
import net.buildertools.util.FreeBlockRaycast;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.Map;

/**
 * Draws the mod's rotated blocks with FULL block shading every frame (the PlaceAnywhere
 * technique): instead of mixing into the chunk compiler, each rotated block's real model is
 * rendered through the vanilla block pipeline with the pose stack rotated around the cell center,
 * so the block keeps its textures, shading and ambient occlusion while spinning in place. The cell
 * itself stays air - this renderer IS the block's visual.
 *
 * <p>Rendered at the {@link RenderLevelStageEvent.Stage#AFTER_LEVEL} stage with the same pose
 * stack and {@code renderSingleBlock} call the off-grid placement preview uses (which is proven to
 * work), so the placed block looks exactly like the preview that placed it. A dark-blue rotated
 * outline shows the block under the cursor.
 */
@OnlyIn(Dist.CLIENT)
public final class RotatedBlockRenderer {
    private static final int OUTLINE_COLOR = 0xFF3A6BFF; // bright blue, Hytale-ish selection accent
    private static final int MAX_RENDER_DIST = 96;

    private RotatedBlockRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        PoseStack poseStack = worldPoseStack(event);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Vec3 camera = event.getCamera().getPosition();
        double range = MAX_RENDER_DIST * MAX_RENDER_DIST;

        Player player = minecraft.player;

        // Aimed rotated block (the rotated outline to show).
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        FreeBlockRaycast.Hit aimed = FreeBlockRaycast.raycast(minecraft.level, eye,
                eye.add(look.scale(6.0)));

        for (Map.Entry<BlockPos, RotationData> e : RotationStore.clientEntries()) {
            BlockPos pos = e.getKey();
            RotationData rot = e.getValue();
            Vec3 center = Vec3.atCenterOf(pos);
            if (camera.distanceToSqr(center) > range) {
                continue;
            }
            BlockState state = rot.state();
            if (state == null || state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            // Billboard blocks always face the player (Hytale), like the placement preview.
            Quaternionf quat;
            if (rot.billboard()) {
                double dx = center.x - camera.x;
                double dy = center.y - camera.y;
                double dz = center.z - camera.z;
                double h = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4)));
                quat = OffGridTransform.rotation(yaw, pitch);
            } else {
                quat = OffGridTransform.rotation(rot.yaw(), rot.pitch());
            }

            poseStack.pushPose();
            // Same transform the placement preview uses: translate to the cell, rotate the model
            // around the cell center (0.5, 0.5, 0.5) so it spins in place. The base pose stack
            // already maps world -> camera space, so the cell corner goes in as WORLD coordinates
            // (no camera subtraction - that would double-offset every block by the camera pos).
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
            poseStack.translate(0.5f, 0.5f, 0.5f);
            poseStack.mulPose(quat);
            poseStack.translate(-0.5f, -0.5f, -0.5f);
            minecraft.getBlockRenderer().renderSingleBlock(
                    state, poseStack, buffers,
                    LevelRenderer.getLightColor(minecraft.level, pos),
                    OverlayTexture.NO_OVERLAY);
            poseStack.popPose();

            // Rotated outline for the block under the cursor.
            if (aimed != null && aimed.cell().equals(pos)) {
                drawRotatedOutline(poseStack, buffers, pos, quat, state, camera);
            }
        }
        buffers.endBatch();
    }

    /** Draws the 12 edges of the block's own shape bounds rotated around the cell center. */
    private static void drawRotatedOutline(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                           BlockPos pos, Quaternionf quat, BlockState state, Vec3 camera) {
        AABB shape = state.getCollisionShape(Minecraft.getInstance().level, pos).bounds();
        poseStack.pushPose();
        poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
        poseStack.translate(0.5f, 0.5f, 0.5f);
        poseStack.mulPose(quat);
        poseStack.translate(-0.5f, -0.5f, -0.5f);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        float r = ((OUTLINE_COLOR >> 16) & 0xFF) / 255.0f;
        float g = ((OUTLINE_COLOR >> 8) & 0xFF) / 255.0f;
        float b = (OUTLINE_COLOR & 0xFF) / 255.0f;
        float a = ((OUTLINE_COLOR >> 24) & 0xFF) / 255.0f;
        LevelRenderer.renderLineBox(poseStack, lines, shape.minX, shape.minY, shape.minZ,
                shape.maxX, shape.maxY, shape.maxZ, r, g, b, a);
        poseStack.popPose();
    }

    /**
     * Builds a pose stack that transforms world coordinates into the camera view space, cancelling
     * out whatever model-view matrix the render system currently has (same approach as
     * {@link SelectionRenderer}).
     */
    private static PoseStack worldPoseStack(RenderLevelStageEvent event) {
        Vec3 cameraPos = event.getCamera().getPosition();
        Matrix4f view = new Matrix4f(event.getModelViewMatrix());
        view.translate((float) -cameraPos.x, (float) -cameraPos.y, (float) -cameraPos.z);

        Matrix4f global = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());
        Matrix4f poseMatrix;
        if (global.invert().determinant() != 0.0f) {
            poseMatrix = global.mul(view);
        } else {
            poseMatrix = view;
        }

        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(poseMatrix);
        poseStack.last().normal().set(new Matrix3f(poseMatrix));
        return poseStack;
    }
}
