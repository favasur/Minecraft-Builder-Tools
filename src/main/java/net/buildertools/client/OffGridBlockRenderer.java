package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.util.OffGridTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/**
 * Renders the solid legacy Air Placement entity.  The linked BlockDisplay is suppressed by
 * {@code BlockDisplayRendererMixin}; rendering here keeps the Air Placement path identical to the
 * real rotated-block layer: transformed quads, world-space normals, and deterministic world light
 * samples all come from {@link RotatedBlockRendering}.
 */
public class OffGridBlockRenderer extends EntityRenderer<OffGridBlockEntity> {
    public OffGridBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0f;
    }

    @Override
    public void render(OffGridBlockEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        BlockState state = entity.getRepresentedState();
        if (state == null || state.isAir() || state.getRenderShape() != RenderShape.MODEL) {
            return;
        }

        Vec3 center = entity.modelCenter();
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        float yaw = entity.getPlacementYaw();
        float pitch = entity.getPlacementPitch();
        RotatedBlockModel model;
        if (entity.isBillboard()) {
            float[] facing = OffGridTransform.billboardAngles(center, camera);
            yaw = facing[0];
            pitch = facing[1];
            model = RotatedBlockModel.build(state, OffGridTransform.rotation(yaw, pitch));
        } else {
            model = RotatedBlockModel.get(state, yaw, pitch);
        }
        if (model == null) {
            return;
        }

        // EntityRenderDispatcher has already translated the pose to the entity's current position
        // (the bottom-center of its visual collision AABB).  RotatedBlockRendering expects a pose
        // whose local model origin is the cell corner, so bridge from that anchor to the precise
        // fractional model center before emitting the 0..1 baked vertices.
        poseStack.pushPose();
        poseStack.translate(center.x - entity.getX() - 0.5,
                center.y - entity.getY() - 0.5,
                center.z - entity.getZ() - 0.5);
        BlockPos lightPos = BlockPos.containing(center);
        if (!entity.isBillboard()) {
            RotatedBlockTriangles.triangles(state, yaw, pitch, center, lightPos, minecraft.level);
        }
        VertexConsumer consumer = buffer.getBuffer(ItemBlockRenderTypes.getRenderType(state, false));
        RotatedBlockRendering.render(model, state, lightPos, center, poseStack, consumer,
                minecraft.level);
        poseStack.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(OffGridBlockEntity entity) {
        // The model renderer binds the block atlas itself; this method is never used for the
        // custom path, but a valid location keeps generic EntityRenderer bookkeeping harmless.
        return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png");
    }
}
