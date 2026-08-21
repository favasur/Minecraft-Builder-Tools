package net.buildertools.client;

import net.buildertools.server.RotationStore;
import net.buildertools.util.FreeBlockRaycast;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.client.debug.v1.renderer.DebugRendererRegistry;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Draws the mod's rotated blocks every frame using the 26.2 gizmo API (absolute world
 * coordinates, so no camera math). Since 26.2 has no block-model pipeline (no
 * {@code renderSingleBlock}), each rotated block is drawn as its real rotated shape: a translucent
 * filled ghost of the block's own collision bounds rotated around the cell center, plus its 12
 * edges - the same visual language as the off-grid placement preview. The cell itself stays air -
 * this renderer IS the block's visual. The block under the cursor gets a bright blue outline.
 */
public final class RotatedBlockRenderer implements DebugRenderer.SimpleDebugRenderer {
    private static final RotatedBlockRenderer INSTANCE = new RotatedBlockRenderer();

    private static final int EDGE_COLOR = 0xFF8AB8FF;      // soft blue edges
    private static final int FILL_COLOR = 0x2E3A6BFF;      // translucent blue fill
    private static final int AIMED_COLOR = 0xFF3A6BFF;     // bright blue aimed outline
    private static final int AIMED_FILL_COLOR = 0x4E3A6BFF;
    private static final int MAX_RENDER_DIST = 96;

    private RotatedBlockRenderer() {
    }

    /** Attaches this renderer to the per-frame gizmo pass (fabric-debug-api). */
    public static void register() {
        DebugRendererRegistry.register(DebugSubscriptions.DEDICATED_SERVER_TICK_TIME,
                minecraft -> INSTANCE);
    }

    @Override
    public void emitGizmos(double camX, double camY, double camZ,
                           DebugValueAccess debugValueAccess, Frustum frustum, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        Vec3 camera = new Vec3(camX, camY, camZ);
        double range = MAX_RENDER_DIST * MAX_RENDER_DIST;

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

            boolean isAimed = aimed != null && aimed.cell().equals(pos);
            drawRotatedBox(state, pos, center, quat, isAimed);
        }
    }

    /** Draws the block's own shape bounds rotated around the cell center: 6 filled faces + 12 edges. */
    private static void drawRotatedBox(BlockState state, BlockPos pos, Vec3 center,
                                       Quaternionf quat, boolean aimed) {
        Minecraft minecraft = Minecraft.getInstance();
        AABB shape = state.getCollisionShape(minecraft.level, pos).bounds();
        if (shape.getSize() == 0.0) {
            return;
        }
        int edgeColor = aimed ? AIMED_COLOR : EDGE_COLOR;
        int fillColor = aimed ? AIMED_FILL_COLOR : FILL_COLOR;

        // 8 corners of the shape in local (cell) space, rotated around the cell center.
        Vec3[] c = new Vec3[8];
        int idx = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    Vector3f offset = quat.transform(new Vector3f(
                            (float) (shape.minX - 0.5 + dx * (shape.maxX - shape.minX)),
                            (float) (shape.minY - 0.5 + dy * (shape.maxY - shape.minY)),
                            (float) (shape.minZ - 0.5 + dz * (shape.maxZ - shape.minZ))),
                            new Vector3f());
                    c[idx++] = new Vec3(center.x + offset.x, center.y + offset.y, center.z + offset.z);
                }
            }
        }

        // Six faces as translucent quads (any corner order works for a convex quad).
        int[][] faces = {
                {0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        GizmoStyle faceStyle = GizmoStyle.strokeAndFill(edgeColor, 1.0f, fillColor);
        for (int[] face : faces) {
            Gizmos.rect(c[face[0]], c[face[1]], c[face[2]], c[face[3]], faceStyle);
        }

        // The 12 edges, drawn as plain lines (always visible regardless of fill support).
        int[][] edges = {
                {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
                {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}};
        for (int[] edge : edges) {
            Gizmos.line(c[edge[0]], c[edge[1]], edgeColor, 1.5f);
        }
    }
}
