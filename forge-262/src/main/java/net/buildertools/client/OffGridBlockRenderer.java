package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.util.OffGridTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the solid legacy Air Placement entity.  The linked BlockDisplay is suppressed by
 * {@code BlockDisplayRendererMixin}; rendering here keeps the Air Placement path identical to the
 * real rotated-block layer: transformed quads, world-space normals, and deterministic world light
 * samples all come from {@link RotatedBlockRendering}.
 */
public class OffGridBlockRenderer extends EntityRenderer<OffGridBlockEntity, OffGridRenderState> {
    public OffGridBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0f;
    }

    @Override
    public OffGridRenderState createRenderState() {
        return new OffGridRenderState();
    }

    @Override
    public void extractRenderState(OffGridBlockEntity entity, OffGridRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.entity = entity;
    }

    @Override
    public void submit(OffGridRenderState state, PoseStack poseStack,
                       SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        OffGridBlockEntity entity = state.entity;
        if (entity == null || entity.isRemoved()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        BlockState blockState = entity.getRepresentedState();
        if (blockState == null || blockState.isAir()
                || blockState.getRenderShape() != RenderShape.MODEL) {
            return;
        }

        Vec3 center = entity.modelCenter();
        float yaw = entity.getPlacementYaw();
        float pitch = entity.getPlacementPitch();
        RotatedBlockModel model;
        if (entity.isBillboard()) {
            float[] facing = OffGridTransform.billboardAngles(center, camera.pos);
            yaw = facing[0];
            pitch = facing[1];
            model = RotatedBlockModel.build(blockState, OffGridTransform.rotation(yaw, pitch));
        } else {
            model = RotatedBlockModel.get(blockState, yaw, pitch);
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
            RotatedBlockTriangles.triangles(blockState, yaw, pitch, center, lightPos, minecraft.level);
        }
        RotatedBlockRendering.render(model, blockState, lightPos, center, poseStack,
                NeoForgeRenderBuffer.shared(), minecraft.level);
        poseStack.popPose();
    }
}
