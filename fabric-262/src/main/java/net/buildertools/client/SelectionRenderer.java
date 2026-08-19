package net.buildertools.client;

import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.buildertools.client.settings.BuilderSettings;
import net.buildertools.util.OffGridTransform;
import net.buildertools.selection.LaserState;
import net.buildertools.selection.RulerState;
import net.buildertools.selection.SelectionHandles;
import net.buildertools.selection.SelectionManager;
import net.fabricmc.fabric.api.client.debug.v1.renderer.DebugRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.debug.DebugSubscriptions;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * In-world rendering for the builder tools using the 26.2 gizmo API:
 * <ul>
 *   <li>Selection region: translucent cyan volume + bright wireframe + gold corner markers</li>
 *   <li>A white outline around the block under the crosshair, so you know exactly which block
 *       will become the next corner</li>
 *   <li>Selected entity: translucent red box + wireframe + white guide line to the ground</li>
 * </ul>
 * Gizmos use absolute world coordinates, so no camera math is needed. On Fabric the renderer is
 * registered through {@link DebugRendererRegistry} (fabric-debug-api), which attaches it to the
 * vanilla debug renderer pass; the subscription parameter is unused for rendering.
 */
public final class SelectionRenderer implements DebugRenderer.SimpleDebugRenderer {
    private static final SelectionRenderer INSTANCE = new SelectionRenderer();

    private static final int CORNER_COLOR = 0xFFFFC94D; // gold
    private static final int REGION_LINE_COLOR = 0xFF3AE0FF; // bright cyan
    private static final int TARGET_COLOR = 0xFFFFFFFF; // white
    private static final int ENTITY_COLOR = 0xFFFF5A5A; // red

    private static final int REGION_FILL_COLOR = 0x262EBFFF; // translucent cyan
    private static final int ENTITY_FILL_COLOR = 0x26FF5A5A; // translucent red

    private SelectionRenderer() {
    }

    /** Attaches this renderer to the per-frame gizmo pass. */
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

        ItemStack held = player.getMainHandItem();
        Item item = held.getItem();

        // The selection region and the selected entity persist across item switches, so they are
        // drawn whenever they exist, no matter what is in the hand.
        if (SelectionManager.hasSelection()) {
            renderSelection(item instanceof SelectionToolItem);
        }
        if (SelectionManager.hasSelectedEntity()) {
            renderEntity();
        }
        if (item instanceof RulerToolItem) {
            renderRuler();
        } else if (item instanceof LaserToolItem) {
            renderLaser(player);
        } else if (isBrush(item)) {
            renderBrushPreview(player);
        }

        // Off-grid placement preview: the block about to be placed, rotated around its cell.
        if (BlockRotateState.isActive()) {
            renderOffGridPreview(player);
        }
    }

    private static boolean isBrush(Item item) {
        return item instanceof PaintToolItem || item instanceof ScatterToolItem || item instanceof SmoothToolItem;
    }

    /** Fills a box using the current selection opacity setting. */
    private static void filledBox(AABB box, int baseFill, int lineColor) {
        float scale = BuilderSettings.getSelectionOpacity() / 0.15f;
        int alpha = (int) (Math.min(1.0f, scale) * ((baseFill >>> 24) & 0xFF));
        int fill = (alpha << 24) | (baseFill & 0x00FFFFFF);
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(lineColor, 2.0f, fill));
    }

    /** Wireframe sphere at the current brush target (radius 4, matching the server's brush). */
    private static void renderBrushPreview(Player player) {
        HitResult hit = player.pick(BuilderSettings.getToolReach(), 1.0f, false);
        Vec3 center;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            center = Vec3.atCenterOf(((BlockHitResult) hit).getBlockPos());
        } else if (BuilderSettings.isAirPlacement()) {
            center = player.getEyePosition(1.0f)
                    .add(player.getLookAngle().scale(BuilderSettings.getAirPlaceDistance()));
        } else {
            return;
        }

        int alpha = (int) (Math.min(1.0f, BuilderSettings.getBrushOpacity()) * 255);
        int color = (alpha << 24) | 0x66CCFF;
        drawRing(center, 4.5, 0, color);   // XY ring
        drawRing(center, 4.5, 1, color);   // XZ ring
        drawRing(center, 4.5, 2, color);   // YZ ring
    }

    /** Draws one axis-aligned circle of line segments around {@code center}. */
    private static void drawRing(Vec3 center, double r, int plane, int color) {
        int segments = 48;
        Vec3 prev = null;
        for (int i = 0; i <= segments; i++) {
            double a = Math.PI * 2 * i / segments;
            double u = Math.cos(a) * r;
            double v = Math.sin(a) * r;
            Vec3 p = switch (plane) {
                case 0 -> new Vec3(center.x + u, center.y + v, center.z);
                case 1 -> new Vec3(center.x + u, center.y, center.z + v);
                default -> new Vec3(center.x, center.y + u, center.z + v);
            };
            if (prev != null) {
                Gizmos.line(prev, p, color);
            }
            prev = p;
        }
    }

    private static void renderSelection(boolean showTarget) {
        // Translucent region volume + bright wireframe (selection box).
        if (SelectionManager.hasSelection()) {
            BlockPos min = SelectionManager.getMin();
            BlockPos max = SelectionManager.getMax();
            AABB box = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
            filledBox(box, REGION_FILL_COLOR, REGION_LINE_COLOR);

            // Drag buttons: a flat dark-blue plate at the centre of each face. The
            // plate under the crosshair lights up so grabbing is obvious from any distance.
            Player viewer = Minecraft.getInstance().player;
            SelectionHandles.Hit hover = viewer != null
                    ? SelectionHandles.raycast(SelectionHandles.handles(min, max),
                            viewer.getEyePosition(1.0f), viewer.getLookAngle(), 64.0)
                    : null;
            // The plate fill alpha is adjustable from the Creative Settings panel (Selection Panel Opacity).
            float panelAlpha = Math.min(1.0f, BuilderSettings.getSelectionPanelOpacity() / 0.55f);
            for (SelectionHandles.Handle handle : SelectionHandles.handles(min, max)) {
                boolean active = HandleDragState.isDragging()
                        && HandleDragState.axis() == handle.axis()
                        && HandleDragState.positive() == handle.positive();
                boolean hovered = hover != null && hover.handle() == handle;
                int fill;
                int wire;
                if (active) {
                    // Orange while stretching (Alt+drag), gold for a plain resize drag.
                    fill = HandleDragState.isStretch() ? 0xD96B4428 : 0xD9524A1A;
                    wire = HandleDragState.isStretch() ? 0xFFFF9A66 : 0xFFFFE066;
                } else if (hovered) {
                    fill = 0xCC294766;
                    wire = 0xFF9FB2FF;
                } else {
                    fill = 0x8C17263B;
                    wire = 0xFF5A6CFF;
                }
                int alpha = (int) (panelAlpha * ((fill >>> 24) & 0xFF));
                int fillFinal = (alpha << 24) | (fill & 0x00FFFFFF);
                Gizmos.cuboid(handle.box(), GizmoStyle.strokeAndFill(wire, 1.5f, fillFinal));
            }
        }

        // Gold markers on the set corners.
        BlockPos corner1 = SelectionManager.getCorner1();
        BlockPos corner2 = SelectionManager.getCorner2();

        // Live preview while picking the second corner: the region grows from corner 1 to the
        // block under the crosshair, so you see the exact area before you click.
        if (corner1 != null && corner2 == null) {
            HitResult hit = Minecraft.getInstance().hitResult;
            if (hit instanceof BlockHitResult blockHit) {
                BlockPos target = blockHit.getBlockPos();
                BlockPos pMin = new BlockPos(
                        Math.min(corner1.getX(), target.getX()),
                        Math.min(corner1.getY(), target.getY()),
                        Math.min(corner1.getZ(), target.getZ()));
                BlockPos pMax = new BlockPos(
                        Math.max(corner1.getX(), target.getX()),
                        Math.max(corner1.getY(), target.getY()),
                        Math.max(corner1.getZ(), target.getZ()));
                AABB preview = new AABB(pMin.getX(), pMin.getY(), pMin.getZ(),
                        pMax.getX() + 1, pMax.getY() + 1, pMax.getZ() + 1);
                Gizmos.cuboid(preview, GizmoStyle.strokeAndFill(0xFFD8F0FF, 1.5f, 0x1240D9FF));
            }
        }

        if (corner1 != null) {
            drawCornerMarker(corner1);
        }
        if (corner2 != null) {
            drawCornerMarker(corner2);
        }

        // White outline around the block under the crosshair (only with the Selection Tool held,
        // so it stays a "what will I mark next?" cue rather than a general highlight).
        if (showTarget) {
            HitResult hit = Minecraft.getInstance().hitResult;
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos pos = ((BlockHitResult) hit).getBlockPos();
                Gizmos.cuboid(new AABB(pos), GizmoStyle.stroke(TARGET_COLOR, 1.5f));
            }
        }
    }

    /**
     * Preview for off-grid placement: the rotated cell footprint (so the player sees exactly how
     * the block will sit), plus a wireframe rotation ring and a marker at the current yaw.
     */
    private static void renderOffGridPreview(Player player) {
        BlockPos target = BlockRotateState.getTarget();
        if (target == null) {
            return;
        }
        org.joml.Quaternionf rot = OffGridTransform.rotation(BlockRotateState.getYawDeg(), BlockRotateState.getPitchDeg());
        Vec3 center = Vec3.atCenterOf(target);

        // Rotated footprint: the 12 edges of the cell cube, rotated around the cell center so
        // the preview matches exactly what the placed display will show.
        Vec3[] corners = new Vec3[8];
        int idx = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    org.joml.Vector3f offset = rot.transform(
                            new org.joml.Vector3f(dx - 0.5f, dy - 0.5f, dz - 0.5f), new org.joml.Vector3f());
                    corners[idx++] = new Vec3(
                            center.x + offset.x,
                            center.y + offset.y,
                            center.z + offset.z);
                }
            }
        }
        int[][] edges = {{0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3}, {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}};
        for (int[] edge : edges) {
            Gizmos.line(corners[edge[0]], corners[edge[1]], 0xFF9FD8FF);
        }

        // Horizontal ring + marker along the block's rotated front (yaw direction).
        drawRing(center, 0.85, 1, 0xFF9FD8FF);
        org.joml.Vector3f front = rot.transform(new org.joml.Vector3f(0.5f, 0, 0), new org.joml.Vector3f());
        double fl = Math.sqrt(front.x * front.x + front.z * front.z);
        if (fl > 1.0E-4) {
            Gizmos.line(center, center.add(front.x / fl * 0.85, 0, front.z / fl * 0.85), 0xFFFFFFFF);
        }
    }

    private static void renderEntity() {
        Entity entity = SelectionManager.getSelectedEntity();
        if (entity == null || entity.isRemoved()) {
            return;
        }
        AABB box = entity.getBoundingBox().inflate(0.15);
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(ENTITY_COLOR, 2.0f, ENTITY_FILL_COLOR));

        // A thin column from the entity's feet down to the ground makes its height clearer.
        double groundY = entity.level()
                .getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, entity.blockPosition())
                .getY();
        AABB column = new AABB(entity.getX() - 0.05, groundY, entity.getZ() - 0.05,
                entity.getX() + 0.05, entity.getY(), entity.getZ() + 0.05);
        Gizmos.cuboid(column, GizmoStyle.stroke(0xFFFFFFFF, 1.0f));

        // Hytale rotate mode: a horizontal ring around the entity with a marker pointing along
        // the current yaw. Gold for body rotation, orange for head-only (Alt+R) rotation.
        if (EntityRotateState.isActive() && EntityRotateState.getEntity() == entity) {
            double radius = entity.getBbWidth() * 0.75 + 0.7;
            Vec3 center = new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
            int ringColor = EntityRotateState.isHeadMode() ? 0xFFFF9A66 : 0xFFFFC94D;
            drawRing(center, radius, 1, ringColor);
            float yaw = EntityRotateState.isHeadMode() ? entity.getYHeadRot() : entity.getYRot();
            Vec3 dir = Vec3.directionFromRotation(0.0f, yaw);
            Gizmos.line(center, center.add(dir.scale(radius)), 0xFFFFFFFF);
        }
    }

    private static void renderRuler() {
        BlockPos a = RulerState.getPointA();
        BlockPos b = RulerState.getPointB();

        if (a != null) {
            drawMarkerCube(a);
        }
        if (b != null) {
            drawMarkerCube(b);
        }
        if (RulerState.hasMeasurement()) {
            Vec3 va = Vec3.atCenterOf(a);
            Vec3 vb = Vec3.atCenterOf(b);
            // Straight measured segment.
            Gizmos.line(va, vb, 0xFFFFFFFF);
            // Axis guides: X red, Y green, Z blue from point A.
            Vec3 corner = new Vec3(vb.x, va.y, va.z);
            Vec3 corner2 = new Vec3(vb.x, vb.y, va.z);
            Gizmos.line(va, corner, 0xFFFF5555);
            Gizmos.line(corner, corner2, 0xFF55FF55);
            Gizmos.line(corner2, vb, 0xFF5555FF);
        }
    }

    private static void renderLaser(Player player) {
        // Raycast up to 128 blocks along the player's look direction.
        HitResult hit = player.pick(128.0, 1.0f, false);
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 end = hit != null ? hit.getLocation() : eye.add(player.getLookAngle().scale(128.0));

        // Beam.
        Gizmos.line(eye, end, 0xFFFF3030);

        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            // Highlight the hit block.
            Gizmos.cuboid(new AABB(pos), GizmoStyle.strokeAndFill(0xFFFF6060, 1.5f, 0x2EFF3333));

            // Show the reading only when the target changes.
            if (!pos.equals(LaserState.getLastTarget())) {
                LaserState.update(pos, eye.distanceTo(end));
                ((LocalPlayer) player).sendOverlayMessage(Component.literal(String.format(Locale.ROOT,
                        "Laser: %.1f blocks", eye.distanceTo(end))));
            }
        }
    }

    private static void drawMarkerCube(BlockPos pos) {
        AABB marker = cornerMarker(pos);
        Gizmos.cuboid(marker, GizmoStyle.strokeAndFill(CORNER_COLOR, 1.5f, 0x99FFC94D));
    }

    private static void drawCornerMarker(BlockPos pos) {
        AABB marker = cornerMarker(pos);
        // Filled gold cube + wireframe edge.
        Gizmos.cuboid(marker, GizmoStyle.strokeAndFill(CORNER_COLOR, 1.5f, 0x8CFFC94D));
    }

    private static AABB cornerMarker(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double size = 0.45;
        return new AABB(center.x - size / 2, center.y - size / 2, center.z - size / 2,
                center.x + size / 2, center.y + size / 2, center.z + size / 2);
    }
}
