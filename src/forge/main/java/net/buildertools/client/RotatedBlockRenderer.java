package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.buildertools.server.RotationStore;
import net.buildertools.util.ArchGeometry;
import net.buildertools.util.EllipseGeometry;
import net.buildertools.util.FreeBlockRaycast;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;
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
 * <p>Rendered at the {@link RenderLevelStageEvent.Stage#AFTER_LEVEL} stage, so the placed block
 * looks exactly like the placement preview (which renders the same way). A dark-blue rotated
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
            Vec3 center = rot.center(pos);
            if (camera.distanceToSqr(center) > range) {
                continue;
            }
            BlockState state = rot.state();
            if (state == null || state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
                continue;
            }
            // Arch / ellipse voussoirs are rendered from their wedge geometry (world-space
            // textured quads) instead of a rotated baked model - the pose stays at the
            // world-to-camera matrix.
            if (rot.arch() != null) {
                poseStack.pushPose();
                List<BakedQuad> quads = ArchGeometry.wedgeQuads(rot.arch(), state);
                VertexConsumer archConsumer = buffers.getBuffer(
                        net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderType(state, false));
                RotatedBlockRendering.renderWorldQuads(state, poseStack.last(), archConsumer,
                        minecraft.level, quads);
                poseStack.popPose();
                continue;
            }
            if (rot.ellipse() != null) {
                poseStack.pushPose();
                List<BakedQuad> quads = EllipseGeometry.wedgeQuads(rot.ellipse(), state);
                VertexConsumer ellipseConsumer = buffers.getBuffer(
                        net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderType(state, false));
                RotatedBlockRendering.renderWorldQuads(state, poseStack.last(), ellipseConsumer,
                        minecraft.level, quads);
                poseStack.popPose();
                continue;
            }
            // Billboard blocks always face the player (Hytale), like the placement preview.
            Quaternionf quat;
            BakedModel rotatedModel;
            if (rot.billboard()) {
                double dx = center.x - camera.x;
                double dy = center.y - camera.y;
                double dz = center.z - camera.z;
                double h = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4)));
                quat = OffGridTransform.rotation(yaw, pitch);
                rotatedModel = RotatedBlockModel.build(state, quat);
            } else {
                quat = OffGridTransform.rotation(rot.yaw(), rot.pitch());
                rotatedModel = RotatedBlockModel.get(state, rot.yaw(), rot.pitch());
            }

            poseStack.pushPose();
            // The model geometry is pre-rotated around its center (see RotatedBlockModel), so the
            // pose only places the local 0..1 model box at the exact model center (fractional for
            // blocks snapped onto a rotated neighbor's grid). The base pose stack already maps
            // world -> camera space, so the center goes in as WORLD coordinates.
            poseStack.translate(center.x - 0.5, center.y - 0.5, center.z - 0.5);
            // Render the rotated geometry with per-face shading that follows the block's true
            // world-space orientation (see RotatedBlockRendering) instead of the vanilla
            // axis-quantized pipeline, so the rotated block blends with its surroundings from any
            // viewing angle instead of reading darker than the blocks around it.
            if (rotatedModel != null) {
                VertexConsumer consumer = buffers.getBuffer(
                        net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderType(state, false));
                RotatedBlockRendering.render(rotatedModel, state, pos, center, poseStack, consumer,
                        minecraft.level);
            }
            poseStack.popPose();

            // Rotated outline for the block under the cursor.
            if (aimed != null && aimed.cell().equals(pos)) {
                drawRotatedOutline(poseStack, buffers, center, quat, state);
            }
        }
        buffers.endBatch();
    }

    /** Draws the 12 edges of the block's own shape bounds rotated around its model center. */
    private static void drawRotatedOutline(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                           Vec3 center, Quaternionf quat, BlockState state) {
        AABB shape = state.getCollisionShape(Minecraft.getInstance().level, BlockPos.ZERO).bounds();
        poseStack.pushPose();
        poseStack.translate(center.x, center.y, center.z);
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
        // Forge 1.21.1 exposes the pose stack instead of the model-view matrix.
        Matrix4f view = new Matrix4f(event.getPoseStack());
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
