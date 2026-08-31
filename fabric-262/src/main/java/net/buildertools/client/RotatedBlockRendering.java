package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Renders a pre-rotated block model (see {@link RotatedBlockModel}) with per-face shading that
 * follows the block's REAL world-space geometry instead of the vanilla axis-quantized pipeline.
 *
 * <p>The vanilla {@code BlockModelLighter} shades every quad by the axis direction its normal
 * snaps to, so a face pitched 45-60 degrees reads as a plain side face (0.6/0.8) instead of the
 * ~0.9-1.0 it should - making rotated blocks look darker than their neighbors, and darker still
 * from above where the pitched faces are most visible. This emitter instead computes a continuous
 * directional shade from the quad's exact world-space normal (identical to vanilla's 1.0/0.8/0.6/
 * 0.5 for axis-aligned faces, smooth in between) and samples the block light at each corner's
 * actual world position (nudged along the face normal into the neighboring cell), so tilted faces
 * get the brightness they deserve and faces touching walls or ground darken naturally.
 */
public final class RotatedBlockRendering {
    private static final double LIGHT_NUDGE = 0.01;

    private RotatedBlockRendering() {
    }

    /**
     * Renders every quad of the rotated model through the caller's buffer (submitted as custom
     * geometry at the end of the frame). Mirrors the quad enumeration of the vanilla block-model
     * pipeline - all six directions plus the direction-less list, same seed per pass - so nothing
     * is skipped, and applies the block's model offset and quad tints the same way vanilla does.
     *
     * @param center the world-space rotation pivot of the model (the pose must already be
     *               translated by {@code center - 0.5}, matching how {@link RotatedBlockModel}
     *               bakes its geometry)
     */
    public static void render(RotatedBlockModel model, BlockState state, BlockPos pos, Vec3 center,
                              PoseStack poseStack, NeoForgeRenderBuffer buffer,
                              BlockAndTintGetter level) {
        Vec3 offset = state.getOffset(pos);
        if (offset.x != 0.0 || offset.y != 0.0 || offset.z != 0.0) {
            poseStack.translate(offset.x, offset.y, offset.z);
        }
        renderQuadList(state, pos, center, offset, poseStack, buffer, level,
                model.allQuads(state, state.getSeed(pos)));
    }

    private static void renderQuadList(BlockState state, BlockPos pos, Vec3 center, Vec3 offset,
                                       PoseStack poseStack, NeoForgeRenderBuffer buffer,
                                       BlockAndTintGetter level, List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return;
        }
        QuadInstance instance = new QuadInstance();
        instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        float[] normal = new float[3];
        int[] lights = new int[4];
        for (BakedQuad quad : quads) {
            // The quad positions are already block-local 0..1 values; dividing them by 16 here
            // would sample almost the entire face at the block centre and make the apparent
            // lighting change as the camera crossed a cell boundary.
            quadNormal(quad, normal);
            float shade = quad.materialInfo().shade()
                    ? smoothShade(normal[0], normal[1], normal[2]) : 1.0F;

            float tr = 1.0F;
            float tg = 1.0F;
            float tb = 1.0F;
            if (quad.materialInfo().isTinted()) {
                int color = Minecraft.getInstance().getBlockColors()
                        .getTintSource(state, quad.materialInfo().tintIndex())
                        .colorInWorld(state, level, pos);
                tr = ARGB.redFloat(color);
                tg = ARGB.greenFloat(color);
                tb = ARGB.blueFloat(color);
            }

            // Nudge each corner a hair along the face normal so the sampled cell is the one the
            // face points into (open air for exposed faces, the wall for touching faces).
            for (int i = 0; i < 4; i++) {
                double wx = center.x - 0.5 + offset.x + quad.position(i).x()
                        + normal[0] * LIGHT_NUDGE;
                double wy = center.y - 0.5 + offset.y + quad.position(i).y()
                        + normal[1] * LIGHT_NUDGE;
                double wz = center.z - 0.5 + offset.z + quad.position(i).z()
                        + normal[2] * LIGHT_NUDGE;
                lights[i] = LightCoordsUtil.getLightCoords(
                        level, BlockPos.containing(wx, wy, wz));
            }

            int tint = ARGB.color(255,
                    (int) (tr * 255.0F), (int) (tg * 255.0F), (int) (tb * 255.0F));
            for (int i = 0; i < 4; i++) {
                instance.setColor(i, ARGB.scaleRGB(tint, shade));
                instance.setLightCoords(i, lights[i]);
            }
            buffer.putBakedQuad(poseStack, quad, instance);
        }
    }

    /**
     * Renders world-space quads (the arch / ellipse wedge geometry, whose vertex positions are
     * already in world coordinates - no center/offset translation) with the same per-face shade
     * and per-corner world light as the rotated-model path.
     */
    public static void renderWorldQuads(BlockState state, PoseStack poseStack, NeoForgeRenderBuffer buffer,
                                        BlockAndTintGetter level, List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return;
        }
        QuadInstance instance = new QuadInstance();
        instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        float[] normal = new float[3];
        int[] lights = new int[4];
        for (BakedQuad quad : quads) {
            quadNormal(quad, normal);
            float shade = quad.materialInfo().shade()
                    ? smoothShade(normal[0], normal[1], normal[2]) : 1.0F;

            float tr = 1.0F;
            float tg = 1.0F;
            float tb = 1.0F;
            if (quad.materialInfo().isTinted()) {
                int color = Minecraft.getInstance().getBlockColors()
                        .getTintSource(state, quad.materialInfo().tintIndex())
                        .colorInWorld(state, level, BlockPos.ZERO);
                tr = ARGB.redFloat(color);
                tg = ARGB.greenFloat(color);
                tb = ARGB.blueFloat(color);
            }

            // Nudge each corner a hair along the face normal so the sampled cell is the one the
            // face points into (open air for exposed faces, the wall for touching faces). The
            // quad positions are already world coordinates.
            for (int i = 0; i < 4; i++) {
                double wx = quad.position(i).x() + normal[0] * LIGHT_NUDGE;
                double wy = quad.position(i).y() + normal[1] * LIGHT_NUDGE;
                double wz = quad.position(i).z() + normal[2] * LIGHT_NUDGE;
                lights[i] = LightCoordsUtil.getLightCoords(
                        level, BlockPos.containing(wx, wy, wz));
            }

            int tint = ARGB.color(255,
                    (int) (tr * 255.0F), (int) (tg * 255.0F), (int) (tb * 255.0F));
            for (int i = 0; i < 4; i++) {
                instance.setColor(i, ARGB.scaleRGB(tint, shade));
                instance.setLightCoords(i, lights[i]);
            }
            buffer.putBakedQuad(poseStack, quad, instance);
        }
    }

    /** World-space unit normal of the quad, computed from its (pre-rotated) geometry. */
    private static void quadNormal(BakedQuad quad, float[] out) {
        float x0 = quad.position(0).x();
        float y0 = quad.position(0).y();
        float z0 = quad.position(0).z();
        float x1 = quad.position(1).x();
        float y1 = quad.position(1).y();
        float z1 = quad.position(1).z();
        float x2 = quad.position(2).x();
        float y2 = quad.position(2).y();
        float z2 = quad.position(2).z();
        float nx = (y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0);
        float ny = (z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0);
        float nz = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-5F) {
            Direction d = quad.direction();
            out[0] = d.step().x();
            out[1] = d.step().y();
            out[2] = d.step().z();
            return;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        // Preserve the baked quad's winding. Some model loaders emit the four vertices in the
        // opposite order; without this correction the same face is lit from its back side and the
        // result appears to change when the camera moves around it.
        Direction d = quad.direction();
        if (nx * d.step().x() + ny * d.step().y() + nz * d.step().z() < 0.0F) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        out[0] = nx;
        out[1] = ny;
        out[2] = nz;
    }

    /**
     * Continuous directional shade for a face with the given world-space normal: exact vanilla
     * values for axis-aligned faces (top 1.0, bottom 0.5, N/S 0.8, E/W 0.6) and a smooth blend
     * between the adjacent axes for tilted faces, so a 45 degree face reads ~0.9 instead of
     * snapping down to a plain side shade.
     */
    private static float smoothShade(float nx, float ny, float nz) {
        float ax = Math.abs(nx);
        float ay = Math.abs(ny);
        float az = Math.abs(nz);
        float s = 0.6F * ax + (ny >= 0.0F ? 1.0F : 0.5F) * ay + 0.8F * az;
        float w = ax + ay + az;
        return w > 1.0E-6F ? s / w : 1.0F;
    }
}
