package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Renders a pre-rotated block model (see {@link RotatedBlockModel}) with per-face shading that
 * follows the block's REAL world-space geometry instead of the vanilla axis-quantized pipeline.
 *
 * <p>The vanilla {@code ModelBlockRenderer} shades every quad by the axis direction its normal
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
     * Renders every quad of the rotated model through the caller's consumer (already bound to the
     * right render type). Mirrors the quad enumeration of {@code ModelBlockRenderer#tesselateBlock}
     * - all six directions plus the direction-less list, same seed per pass - so nothing is
     * skipped, and applies the block's model offset and quad tints the same way vanilla does.
     *
     * @param center the world-space rotation pivot of the model (the pose must already be
     *               translated by {@code center - 0.5}, matching how {@link RotatedBlockModel}
     *               bakes its geometry)
     */
    public static void render(RotatedBlockModel model, BlockState state, BlockPos pos, Vec3 center,
                              PoseStack poseStack, VertexConsumer consumer, BlockAndTintGetter level) {
        Vec3 offset = state.getOffset(level, pos);
        if (offset.x != 0.0 || offset.y != 0.0 || offset.z != 0.0) {
            poseStack.translate(offset.x, offset.y, offset.z);
        }
        PoseStack.Pose pose = poseStack.last();
        renderQuadList(state, pos, center, offset, pose, consumer, level,
                model.allQuads(state, state.getSeed(pos)), false);
    }

    /**
     * Renders quads whose vertex positions are already WORLD space (the arch wedge geometry): no
     * center/offset translation - the pose is the plain world-to-camera matrix - and the light is
     * sampled at each vertex's actual world position. Shading, tint and world-space normals work
     * exactly like {@link #render}.
     */
    public static void renderWorldQuads(BlockState state, PoseStack.Pose pose, VertexConsumer consumer,
                                        BlockAndTintGetter level, List<BakedQuad> quads) {
        renderQuadList(state, BlockPos.ZERO, Vec3.ZERO, Vec3.ZERO, pose, consumer, level, quads, true);
    }

    private static void renderQuadList(BlockState state, BlockPos pos, Vec3 center, Vec3 offset,
                                       PoseStack.Pose pose, VertexConsumer consumer,
                                       BlockAndTintGetter level, List<BakedQuad> quads,
                                       boolean worldSpace) {
        if (quads.isEmpty()) {
            return;
        }
        float[] normal = new float[3];
        int[] lights = new int[4];
        for (BakedQuad quad : quads) {
            // The positions in DefaultVertexFormat.BLOCK are already block-local 0..1 values;
            // dividing them by 16 here sampled almost the entire face at the block centre and
            // made the apparent lighting change as the camera crossed a cell boundary.
            quadNormal(quad, normal);
            float shade = quad.isShade() ? smoothShade(normal[0], normal[1], normal[2]) : 1.0F;

            float tr = 1.0F;
            float tg = 1.0F;
            float tb = 1.0F;
            if (quad.isTinted()) {
                int color = Minecraft.getInstance().getBlockColors()
                        .getColor(state, level, pos, quad.getTintIndex());
                tr = (float) (color >> 16 & 255) / 255.0F;
                tg = (float) (color >> 8 & 255) / 255.0F;
                tb = (float) (color & 255) / 255.0F;
            }

            int[] v = quad.getVertices();
            for (int i = 0; i < 4; i++) {
                int o = i * 8;
                // Nudge the corner a hair along the face normal so the sampled cell is the one
                // the face points into (open air for exposed faces, the wall for touching faces).
                double wx;
                double wy;
                double wz;
                if (worldSpace) {
                    wx = Float.intBitsToFloat(v[o]) + normal[0] * LIGHT_NUDGE;
                    wy = Float.intBitsToFloat(v[o + 1]) + normal[1] * LIGHT_NUDGE;
                    wz = Float.intBitsToFloat(v[o + 2]) + normal[2] * LIGHT_NUDGE;
                } else {
                    wx = center.x - 0.5 + offset.x + Float.intBitsToFloat(v[o])
                            + normal[0] * LIGHT_NUDGE;
                    wy = center.y - 0.5 + offset.y + Float.intBitsToFloat(v[o + 1])
                            + normal[1] * LIGHT_NUDGE;
                    wz = center.z - 0.5 + offset.z + Float.intBitsToFloat(v[o + 2])
                            + normal[2] * LIGHT_NUDGE;
                }
                lights[i] = LevelRenderer.getLightColor(level, state, BlockPos.containing(wx, wy, wz));
            }

            // Emit the vertex fields directly instead of using VertexConsumer#putBulkData.
            // putBulkData reconstructs the normal from BakedQuad#getDirection (an axis-snapped
            // value) and optionally overwrites it with the quad's packed normal. That is correct
            // for vanilla blocks, but makes an arbitrarily rotated model use a camera/axis
            // dependent normal. This path keeps the exact world-space normal and lightmap stable.
            for (int i = 0; i < 4; i++) {
                int o = i * 8;
                int packedColor = v[o + 3];
                float vr = ((packedColor >> 16) & 0xFF) / 255.0F;
                float vg = ((packedColor >> 8) & 0xFF) / 255.0F;
                float vb = (packedColor & 0xFF) / 255.0F;
                float va = ((packedColor >>> 24) & 0xFF) / 255.0F;
                int light = combineLight(lights[i], v[o + 6]);
                consumer.addVertex(pose,
                                Float.intBitsToFloat(v[o]),
                                Float.intBitsToFloat(v[o + 1]),
                                Float.intBitsToFloat(v[o + 2]))
                        .setColor(vr * shade * tr, vg * shade * tg, vb * shade * tb, va)
                        .setUv(Float.intBitsToFloat(v[o + 4]), Float.intBitsToFloat(v[o + 5]))
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, normal[0], normal[1], normal[2]);
            }
        }
    }

    private static int combineLight(int sampled, int baked) {
        int block = Math.max(sampled & 0xFFFF, baked & 0xFFFF);
        int sky = Math.max((sampled >>> 16) & 0xFFFF, (baked >>> 16) & 0xFFFF);
        return block | (sky << 16);
    }

    /** World-space unit normal of the quad, computed from its (pre-rotated) geometry. */
    private static void quadNormal(BakedQuad quad, float[] out) {
        int[] v = quad.getVertices();
        float x0 = Float.intBitsToFloat(v[0]);
        float y0 = Float.intBitsToFloat(v[1]);
        float z0 = Float.intBitsToFloat(v[2]);
        float x1 = Float.intBitsToFloat(v[8]);
        float y1 = Float.intBitsToFloat(v[9]);
        float z1 = Float.intBitsToFloat(v[10]);
        float x2 = Float.intBitsToFloat(v[16]);
        float y2 = Float.intBitsToFloat(v[17]);
        float z2 = Float.intBitsToFloat(v[18]);
        float nx = (y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0);
        float ny = (z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0);
        float nz = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0E-5F) {
            Direction d = quad.getDirection();
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
        Direction d = quad.getDirection();
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
