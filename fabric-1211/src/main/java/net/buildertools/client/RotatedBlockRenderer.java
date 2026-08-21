package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.buildertools.server.RotationStore;
import net.buildertools.util.FreeBlockRaycast;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
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
import org.joml.Quaternionf;

import java.util.Map;

/**
 * Draws the mod's rotated blocks with FULL block shading every frame (the PlaceAnywhere
 * technique): instead of mixing into the chunk compiler, each rotated block's real model is
 * rendered through the vanilla block pipeline with the pose stack rotated around the cell center,
 * so the block keeps its textures, shading and ambient occlusion while spinning in place. The cell
 * itself stays air - this renderer IS the block's visual.
 *
 * <p>Rendered at the {@code WorldRenderEvents.AFTER_TRANSLUCENT} stage (same pass and pose stack
 * the off-grid placement preview uses), so the placed block looks exactly like the preview that
 * placed it. A dark-blue rotated outline shows the block under the cursor.
 */
public final class RotatedBlockRenderer {
    private static final int OUTLINE_COLOR = 0xFF3A6BFF; // bright blue, Hytale-ish selection accent
    private static final int MAX_RENDER_DIST = 96;

    private RotatedBlockRenderer() {
    }

    public static void register() {
        // AFTER_TRANSLUCENT runs after the world has been drawn; its matrix stack carries the
        // camera view, so world coordinates drawn through it land in the right place.
        WorldRenderEvents.AFTER_TRANSLUCENT.register(RotatedBlockRenderer::onRenderLevel);
    }

    private static void onRenderLevel(WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        var poseStack = context.matrixStack();
        MultiBufferSource buffers = context.consumers();
        Vec3 camera = context.camera().getPosition();
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
            // already maps world -> camera space, so the cell corner goes in as WORLD coordinates.
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
                drawRotatedOutline(poseStack, buffers, pos, quat, state);
            }
        }
    }

    /** Draws the 12 edges of the block's own shape bounds rotated around the cell center. */
    private static void drawRotatedOutline(PoseStack poseStack, MultiBufferSource buffers,
                                           BlockPos pos, Quaternionf quat, BlockState state) {
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
}
