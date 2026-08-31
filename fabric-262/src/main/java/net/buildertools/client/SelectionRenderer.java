package net.buildertools.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.buildertools.client.settings.BuilderSettings;
import net.buildertools.server.RotationStore;
import net.buildertools.util.ArchGeometry;
import net.buildertools.util.EllipseGeometry;
import net.buildertools.util.FullSlabsCompat;
import net.buildertools.util.FreeBlockRaycast;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import net.buildertools.selection.LaserState;
import net.buildertools.selection.RulerState;
import net.buildertools.selection.SelectionHandles;
import net.buildertools.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Locale;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * In-world rendering for the builder tools:
 * <ul>
 *   <li>Selection region: translucent cyan volume + bright wireframe + gold corner markers</li>
 *   <li>A white outline around the block under the crosshair, so you know exactly which block
 *       will become the next corner</li>
 *   <li>Selected entity: translucent red box + wireframe + white guide line to the ground</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class SelectionRenderer {
    private static final int CORNER_COLOR = 0xFFFFC94D; // gold
    private static final int REGION_LINE_COLOR = 0xFF3AE0FF; // bright cyan
    private static final int TARGET_COLOR = 0xFFFFFFFF; // white
    private static final int ENTITY_COLOR = 0xFFFF5A5A; // red

    private static final float REGION_FILL_R = 0.18f;
    private static final float REGION_FILL_G = 0.75f;
    private static final float REGION_FILL_B = 1.0f;
    private static final float REGION_FILL_A = 0.15f;

    private static final float ENTITY_FILL_R = 1.0f;
    private static final float ENTITY_FILL_G = 0.35f;
    private static final float ENTITY_FILL_B = 0.35f;
    private static final float ENTITY_FILL_A = 0.15f;

    private SelectionRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        Item item = held.getItem();

        // The event pose maps world -> camera space (world minus camera); vertices are recorded
        // through it and submitted as custom geometry at the end of the level submission.
        PoseStack poseStack = event.getPoseStack();
        Vec3 cameraPosition = event.getLevelRenderState().cameraRenderState.pos;
        NeoForgeRenderBuffer buffers = NeoForgeRenderBuffer.shared();

        // The selection region and the selected entity persist across item switches (builder-mode
        // builder mode), so they are drawn whenever they exist, no matter what is in the hand.
        if (SelectionManager.hasSelection()) {
            renderSelection(poseStack, buffers, item instanceof SelectionToolItem, cameraPosition);
        }
        // Arching (ALT+A): a bright wireframe around the recorded row so the player sees exactly
        // which blocks will be stretched and arched (green once the stretch is done and the next
        // click arches).
        if (ArchState.isActive() && ArchState.hasRegion()) {
            AABB archBox = ArchState.regionBox();
            int archColor = ArchState.phase() == ArchState.Phase.AWAIT_ARCH
                    ? 0xFF5AFF8A : 0xFF9AE6FF;
            drawBox(poseStack, buffers, archBox, archColor);
            // Once the stretch is done and the next click arches, show the ghost arch: the curve
            // is computed live from the block + face under the crosshair (the exact input the
            // click sends), so it morphs as the player aims and disappears when the click would
            // fail.
            if (ArchState.phase() == ArchState.Phase.AWAIT_ARCH) {
                renderArchGhost(poseStack, buffers);
            }
        }
        // Ellipse (ALT+E): a bright wireframe around the recorded region plus the live ghost of
        // the elliptical voussoir band that will form inside it (computed from the block + face
        // under the crosshair, so it morphs as the player aims and hides when the click would
        // fail).
        if (EllipseState.isActive() && EllipseState.hasRegion()) {
            VertexConsumer ellipseLines = buffers.getBuffer(RenderTypes.lines());
            drawBox(poseStack, buffers, EllipseState.regionBox(), 0xFF9AE6FF);
            renderEllipseGhost(poseStack, ellipseLines);
        }
        if (SelectionManager.hasSelectedEntity()) {
            renderEntity(poseStack, buffers);
        }
        if (item instanceof RulerToolItem) {
            renderRuler(poseStack, buffers);
        } else if (item instanceof LaserToolItem) {
            renderLaser(poseStack, buffers, player);
        } else if (isBrush(item)) {
            renderBrushPreview(poseStack, buffers, player);
        }

        // Off-grid placement preview: the block about to be placed, rotated around its cell.
        if (BlockRotateState.isActive()) {
            renderOffGridPreview(poseStack, buffers, player);
        }

        // Progressive mining of an off-grid block (survival): a dark crack overlay whose opacity
        // grows with the dig progress, so it breaks like a real block instead of popping off.
        if (OffGridMining.isActive()) {
            renderOffGridMining(poseStack, buffers);
        }
        if (FreeBlockMining.isActive()) {
            renderFreeBlockMining(poseStack, buffers, cameraPosition);
        }

        // The rotated-block renderer is registered after this handler; it submits the shared
        // buffer once all overlays have recorded into it.
    }

    private static boolean isBrush(Item item) {
        return item instanceof PaintToolItem || item instanceof ScatterToolItem || item instanceof SmoothToolItem;
    }

    /**
     * Wireframe sphere at the current brush target (radius 4, matching the server's brush),
     * so you see exactly what the Paint/Scatter/Smooth tool will affect. Follows the block under
     * the crosshair up to the configured reach, or the air-place distance when Air Placement is on.
     */
    private static void renderBrushPreview(PoseStack poseStack, NeoForgeRenderBuffer buffers, Player player) {
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
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        drawRing(poseStack, lines, center, 4.5, 0, color);   // XY ring
        drawRing(poseStack, lines, center, 4.5, 1, color);   // XZ ring
        drawRing(poseStack, lines, center, 4.5, 2, color);   // YZ ring
    }

    /** Draws one axis-aligned circle of line segments around {@code center}. */
    private static void drawRing(PoseStack poseStack, VertexConsumer lines, Vec3 center, double r, int plane, int color) {
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
                drawLine(poseStack, lines, prev, p, color);
            }
            prev = p;
        }
    }

    private static void renderSelection(PoseStack poseStack, NeoForgeRenderBuffer buffers, boolean showTarget,
                                        Vec3 cameraPosition) {
        // Translucent region volume + bright wireframe (selection box).
        if (SelectionManager.hasSelection()) {
            BlockPos min = SelectionManager.getMin();
            BlockPos max = SelectionManager.getMax();
            AABB box = new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1, max.getZ() + 1);
            // Fill alpha is adjustable from the Creative Settings panel (Selection Box Opacity).
            float fillAlpha = REGION_FILL_A * (BuilderSettings.getSelectionOpacity() / 0.15f);
            NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, box, REGION_FILL_R, REGION_FILL_G, REGION_FILL_B, Math.min(1.0f, fillAlpha));
            drawBox(poseStack, buffers, box, REGION_LINE_COLOR);

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
                float r;
                float g;
                float b;
                float a;
                int wire;
                if (active) {
                    // Orange while stretching (Alt+drag), gold for a plain resize drag.
                    r = HandleDragState.isStretch() ? 0.42f : 0.32f;
                    g = 0.27f;
                    b = 0.10f;
                    a = 0.85f * panelAlpha;
                    wire = HandleDragState.isStretch() ? 0xFFFF9A66 : 0xFFFFE066;
                } else if (hovered) {
                    r = 0.16f;
                    g = 0.28f;
                    b = 0.85f;
                    a = 0.80f * panelAlpha;
                    wire = 0xFF9FB2FF;
                } else {
                    r = 0.09f;
                    g = 0.15f;
                    b = 0.48f;
                    a = 0.55f * panelAlpha;
                    wire = 0xFF5A6CFF;
                }
                NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, handle.box(), r, g, b, a);
                drawBox(poseStack, buffers, handle.box(), wire);
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
                NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, preview, 0.25f, 0.85f, 1.0f, 0.07f);
                drawBox(poseStack, buffers, preview, 0xFFD8F0FF);
            }
        }

        if (corner1 != null) {
            drawCornerMarker(poseStack, buffers, corner1);
        }
        if (corner2 != null) {
            drawCornerMarker(poseStack, buffers, corner2);
        }

        // White outline around the block under the crosshair (only with the Selection Tool held,
        // so it stays a "what will I mark next?" cue rather than a general highlight).
        if (showTarget) {
            HitResult hit = Minecraft.getInstance().hitResult;
            if (hit instanceof BlockHitResult blockHit) {
                BlockPos pos = blockHit.getBlockPos();
                Minecraft minecraft = Minecraft.getInstance();
                RotationData rotation = RotationStore.get(minecraft.level, pos);
                if (rotation != null) {
                    drawRotatedLaserTarget(poseStack, buffers, rotation, pos, cameraPosition, TARGET_COLOR);
                } else {
                    drawBox(poseStack, buffers, new AABB(pos), TARGET_COLOR);
                }
            }
        }
    }

    /**
     * Preview for off-grid placement: the real block model rotated around the cell center (so the
     * player sees exactly what will be placed), plus a wireframe footprint and rotation ring.
     */
    private static void renderOffGridPreview(PoseStack poseStack, NeoForgeRenderBuffer buffers, Player player) {
        BlockPos target = BlockRotateState.getTarget();
        if (target == null) {
            return;
        }
        Vec3 center = BlockRotateState.getCenter();
        // Billboarded blocks always face the player (the placed display uses CENTER billboard
        // constraints), so the preview turns the model toward the eye instead of the drag
        // rotation; the ring turns green so the mode is visible at a glance.
        float yaw = BlockRotateState.getYawDeg();
        float pitch = BlockRotateState.getPitchDeg();
        boolean billboard = BlockRotateState.isBillboard();
        if (billboard) {
            float[] facing = BlockRotateState.facingAngles(player, center);
            yaw = facing[0];
            pitch = facing[1];
        }
        org.joml.Quaternionf rot = OffGridTransform.rotation(yaw, pitch);

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
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        for (int[] edge : edges) {
            drawLine(poseStack, lines, corners[edge[0]], corners[edge[1]], 0xFF9FD8FF);
        }

        // Horizontal ring + marker along the block's rotated front (yaw direction). Green while
        // the block is billboarded (always facing the player).
        drawRing(poseStack, lines, center, 0.85, 1, billboard ? 0xFF7CFC00 : 0xFF9FD8FF);
        org.joml.Vector3f front = rot.transform(new org.joml.Vector3f(0.5f, 0, 0), new org.joml.Vector3f());
        double fl = Math.sqrt(front.x * front.x + front.z * front.z);
        if (fl > 1.0E-4) {
            drawLine(poseStack, lines, center,
                    center.add(front.x / fl * 0.85, 0, front.z / fl * 0.85), 0xFFFFFFFF);
        }

        // Render the actual block model with the same transform the placed display will use:
        // entity at the cell corner, model rotated around the cell center.
        BlockState state = BlockRotateState.getPreviewState();
        if (state == null) {
            ItemStack held = player.getMainHandItem();
            if (held.getItem() instanceof BlockItem blockItem) {
                state = FullSlabsCompat.normalize(blockItem.getBlock().defaultBlockState());
            }
        }
        if (state != null) {
            RotatedBlockModel rotated = RotatedBlockModel.get(state, yaw, pitch);
            if (rotated != null) {
                poseStack.pushPose();
                // The model geometry is pre-rotated around its center (see RotatedBlockModel), so
                // the pose only places the local 0..1 model box at the exact model center.
                poseStack.translate(center.x - 0.5, center.y - 0.5, center.z - 0.5);
                // Render like the placed blocks (same continuous world-space face shading), so the
                // preview matches exactly what will be placed.
                RotatedBlockRendering.render(rotated, state, target, center, poseStack, buffers,
                        (net.minecraft.client.renderer.block.BlockAndTintGetter) player.level());
                poseStack.popPose();
            }
        }
    }

    /**
     * Crack overlay while progressively mining an off-grid block (survival): a translucent dark
     * fill whose opacity grows with the dig progress, plus a wireframe box, so the player sees
     * the block slowly breaking like a normal block instead of vanishing on the first hit.
     */
    private static void renderOffGridMining(PoseStack poseStack, NeoForgeRenderBuffer buffers) {
        OffGridBlockEntity block = OffGridMining.getTarget();
        if (block == null || block.isRemoved()) {
            return;
        }
        float p = Math.max(0.0f, Math.min(1.0f, OffGridMining.getProgress()));
        AABB box = block.visualCollisionBox();
        // Dark overlay grows with progress; a brightening outline keeps it readable on any block.
        NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, box, 0.0f, 0.0f, 0.0f, 0.08f + 0.55f * p);
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        drawBox(poseStack, buffers, box, 0xFFFFFFFF);
        // Crack-ish diagonals that appear as progress passes each quarter.
        if (p > 0.25f) {
            drawLine(poseStack, lines,
                    new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ), 0xFFFFFFFF);
        }
        if (p > 0.5f) {
            drawLine(poseStack, lines,
                    new Vec3(box.maxX, box.minY, box.minZ), new Vec3(box.minX, box.maxY, box.maxZ), 0xFFFFFFFF);
        }
        if (p > 0.75f) {
            drawLine(poseStack, lines,
                    new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.maxX, box.maxY, box.minZ), 0xFFFFFFFF);
        }
    }

    /**
     * Crack overlay while progressively mining a rotated block (survival) - same visual as the
     * legacy entity path, drawn at the block's cell.
     */
    private static void renderFreeBlockMining(PoseStack poseStack, NeoForgeRenderBuffer buffers,
                                              Vec3 cameraPosition) {
        BlockPos pos = FreeBlockMining.getTarget();
        if (pos == null) {
            return;
        }
        float p = Math.max(0.0f, Math.min(1.0f, FreeBlockMining.getProgress()));
        Minecraft minecraft = Minecraft.getInstance();
        RotationData rotation = minecraft.level == null ? null : RotationStore.get(minecraft.level, pos);
        if (rotation != null) {
            drawRotatedLaserTarget(poseStack, buffers, rotation, pos, cameraPosition, 0xFFFFFFFF);
            return;
        }
        AABB box = new AABB(pos);
        NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, box, 0.0f, 0.0f, 0.0f, 0.08f + 0.55f * p);
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        drawBox(poseStack, buffers, box, 0xFFFFFFFF);
        if (p > 0.25f) {
            drawLine(poseStack, lines,
                    new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.maxY, box.maxZ), 0xFFFFFFFF);
        }
        if (p > 0.5f) {
            drawLine(poseStack, lines,
                    new Vec3(box.maxX, box.minY, box.minZ), new Vec3(box.minX, box.maxY, box.maxZ), 0xFFFFFFFF);
        }
        if (p > 0.75f) {
            drawLine(poseStack, lines,
                    new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.maxX, box.maxY, box.minZ), 0xFFFFFFFF);
        }
    }

    private static void renderEntity(PoseStack poseStack, NeoForgeRenderBuffer buffers) {
        Entity entity = SelectionManager.getSelectedEntity();
        if (entity == null || entity.isRemoved()) {
            return;
        }
        AABB box = entity.getBoundingBox().inflate(0.15);
        NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, box, ENTITY_FILL_R, ENTITY_FILL_G, ENTITY_FILL_B, ENTITY_FILL_A);
        drawBox(poseStack, buffers, box, ENTITY_COLOR);

        // A thin column from the entity's feet down to the ground makes its height clearer.
        double groundY = entity.level()
                .getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, entity.blockPosition())
                .getY();
        AABB column = new AABB(entity.getX() - 0.05, groundY, entity.getZ() - 0.05,
                entity.getX() + 0.05, entity.getY(), entity.getZ() + 0.05);
        drawBox(poseStack, buffers, column, 0xFFFFFFFF);

        // Hytale rotate mode: a horizontal ring around the entity with a marker pointing along
        // the current yaw. Gold for body rotation, orange for head-only (Alt+R) rotation.
        if (EntityRotateState.isActive() && EntityRotateState.getEntity() == entity) {
            double radius = entity.getBbWidth() * 0.75 + 0.7;
            Vec3 center = new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ());
            int ringColor = EntityRotateState.isHeadMode() ? 0xFFFF9A66 : 0xFFFFC94D;
            VertexConsumer ring = buffers.getBuffer(RenderTypes.lines());
            drawRing(poseStack, ring, center, radius, 1, ringColor);
            float yaw = EntityRotateState.isHeadMode() ? entity.getYHeadRot() : entity.getYRot();
            Vec3 dir = Vec3.directionFromRotation(0.0f, yaw);
            drawLine(poseStack, ring, center, center.add(dir.scale(radius)), 0xFFFFFFFF);
        }
    }

    private static void renderRuler(PoseStack poseStack, NeoForgeRenderBuffer buffers) {
        BlockPos a = RulerState.getPointA();
        BlockPos b = RulerState.getPointB();

        if (a != null) {
            drawMarkerCube(poseStack, buffers, a);
        }
        if (b != null) {
            drawMarkerCube(poseStack, buffers, b);
        }
        if (RulerState.hasMeasurement()) {
            Vec3 va = Vec3.atCenterOf(a);
            Vec3 vb = Vec3.atCenterOf(b);
            // Fetch a fresh consumer after the markers.
            VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
            // Straight measured segment.
            drawLine(poseStack, lines, va, vb, 0xFFFFFFFF);
            // Axis guides: X red, Y green, Z blue from point A.
            Vec3 corner = new Vec3(vb.x, va.y, va.z);
            Vec3 corner2 = new Vec3(vb.x, vb.y, va.z);
            drawLine(poseStack, lines, va, corner, 0xFFFF5555);
            drawLine(poseStack, lines, corner, corner2, 0xFF55FF55);
            drawLine(poseStack, lines, corner2, vb, 0xFF5555FF);
        }
    }

    private static void renderLaser(PoseStack poseStack, NeoForgeRenderBuffer buffers, Player player) {
        // Raycast up to 128 blocks along the player's look direction. The normal level clip is
        // also patched to see the rotation layer, but keep an explicit result here so the Laser
        // Tool can draw the exact rendered mesh instead of the vanilla cell AABB.
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 rayEnd = eye.add(player.getLookAngle().scale(128.0));
        HitResult vanilla = player.pick(128.0, 1.0f, false);
        FreeBlockRaycast.Hit rotated = FreeBlockRaycast.raycast(player.level(), eye, rayEnd);
        boolean useRotated = rotated != null
                && (vanilla == null || vanilla.getType() == HitResult.Type.MISS
                || rotated.distSq() <= eye.distanceToSqr(vanilla.getLocation()) + 1.0E-7);
        Vec3 end = useRotated
                ? rotated.point()
                : vanilla != null && vanilla.getType() != HitResult.Type.MISS
                ? vanilla.getLocation() : rayEnd;

        // Beam first, then fetch a fresh consumer for the hit outline.
        drawLine(poseStack, buffers.getBuffer(RenderTypes.lines()), eye, end, 0xFFFF3030);

        if (useRotated) {
            BlockPos pos = rotated.cell();
            RotationData data = RotationStore.get(player.level(), pos);
            if (data != null) {
                // Never draw new AABB(pos) here: the cell is only the storage key and its six
                // vanilla-aligned edges are the invisible red cube reported by the user. The
                // outline is made from the same rendered triangles used for collision/rendering.
                drawRotatedLaserTarget(poseStack, buffers, data, pos,
                        player.getEyePosition(1.0f), 0xFFFF6060);
                updateLaserReading(player, pos, eye.distanceTo(end));
            }
        } else if (vanilla instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            RotationData data = RotationStore.get(player.level(), pos);
            if (data != null) {
                // A storage cell can still win vanilla's comparison when its world state is not
                // air. Never expose that cell's axis-aligned cube as the rotated block's target.
                drawRotatedLaserTarget(poseStack, buffers, data, pos,
                        player.getEyePosition(1.0f), 0xFFFF6060);
            } else {
                NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, new AABB(pos), 1.0f, 0.2f, 0.2f, 0.18f);
                drawBox(poseStack, buffers, new AABB(pos), 0xFFFF6060);
            }
            updateLaserReading(player, pos, eye.distanceTo(end));
        }
    }

    /** Draws the exact rotated render/collision surface used by the Laser Tool. */
    private static void drawRotatedLaserTarget(PoseStack poseStack, NeoForgeRenderBuffer buffers,
                                               RotationData data, BlockPos pos, Vec3 camera, int color) {
        float renderYaw = data.yaw();
        float renderPitch = data.pitch();
        if (data.billboard()) {
            float[] facing = OffGridTransform.billboardAngles(data.center(pos), camera);
            renderYaw = facing[0];
            renderPitch = facing[1];
        }
        List<MeshCollisionShape.Tri> triangles = RotatedBlockTriangles.triangles(
                data, pos, Minecraft.getInstance().level, renderYaw, renderPitch);
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        if (triangles != null) {
            for (MeshCollisionShape.Tri t : triangles) {
                Vec3 a = new Vec3(t.ax, t.ay, t.az);
                Vec3 b = new Vec3(t.bx, t.by, t.bz);
                Vec3 c = new Vec3(t.cx, t.cy, t.cz);
                drawLine(poseStack, lines, a, b, color);
                drawLine(poseStack, lines, b, c, color);
                drawLine(poseStack, lines, c, a, color);
            }
            return;
        }

        VoxelShape base = data.state().getCollisionShape(Minecraft.getInstance().level, pos);
        MeshCollisionShape fallback = MeshCollisionShape.fromVoxelShape(base,
                data.center(pos).x, data.center(pos).y, data.center(pos).z,
                renderYaw, renderPitch);
        fallback.forEachTriangle(triangle -> {
            Vec3 a = new Vec3(triangle.ax, triangle.ay, triangle.az);
            Vec3 b = new Vec3(triangle.bx, triangle.by, triangle.bz);
            Vec3 c = new Vec3(triangle.cx, triangle.cy, triangle.cz);
            drawLine(poseStack, lines, a, b, color);
            drawLine(poseStack, lines, b, c, color);
            drawLine(poseStack, lines, c, a, color);
        });
    }

    private static void updateLaserReading(Player player, BlockPos pos, double distance) {
        if (!pos.equals(LaserState.getLastTarget())) {
            LaserState.update(pos, distance);
            player.sendOverlayMessage(Component.literal(String.format(Locale.ROOT,
                    "Laser: %.1f blocks", distance)));
        }
    }

    /**
     * Ghost preview of the arch the next LMB click will commit: the arch is derived from the
     * stretched region and the block + face currently under the crosshair (the same input the
     * click sends, via {@link ArchGeometry#regionArch}), and the curve is drawn over the row -
     * a bright centerline, dim outer/inner edges of the 1m-thick band, and a dim Rise marker
     * from the chord to the apex. Nothing is drawn when the current aim cannot form an arch, so
     * the player sees exactly what the click would generate before committing.
     */
    private static void renderArchGhost(PoseStack poseStack, NeoForgeRenderBuffer buffers) {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) {
            return;
        }
        ArchGeometry.RegionArch ra = ArchGeometry.regionArch(
                ArchState.regionMin(), ArchState.regionMax(), bhr.getBlockPos(), bhr.getDirection());
        if (ra == null) {
            return;
        }
        ArchGeometry.ArchResult arch = ra.arch();
        Vec3 u = arch.u();
        Vec3 w = arch.w();
        Vec3 o = arch.origin();
        // The depth axis: the server commits one row of voussoirs per wall column offset along v,
        // so the preview draws the same arc shifted to every column the region occupies - a
        // multi-wide wall shows its full curved band instead of just the centerline.
        Vec3 v = u.cross(w);
        double t0 = arch.thetaStart();
        double t1 = t0 - arch.totalAngle();
        VertexConsumer lines = buffers.getBuffer(RenderTypes.lines());
        int bright = 0xFF5AFF8A;
        int dim = 0x805AFF8A;
        int colMin = archColumn(ArchState.regionMin(), ArchState.regionMax(), ra.center(), v, true);
        int colMax = archColumn(ArchState.regionMin(), ArchState.regionMax(), ra.center(), v, false);
        for (int col = colMin; col <= colMax; col++) {
            Vec3 co = o.add(v.scale(col));
            drawArc(poseStack, lines, co, u, w, arch.radius(), t0, t1, bright);
            drawArc(poseStack, lines, co, u, w, arch.radius() + 0.5, t0, t1, dim);
            drawArc(poseStack, lines, co, u, w, arch.radius() - 0.5, t0, t1, dim);
        }
        // Rise marker: from the chord midpoint to the apex of the centerline.
        Vec3 apex = o.add(w.scale(arch.radius()));
        drawLine(poseStack, lines, ra.center(), apex, dim);
    }

    /** The lowest (or highest) column index along the arch's depth axis ({@code v}) among the
     *  region's cell centres - the per-column offsets the server commits
     *  ({@code round((cellCenter - boxCenter)·v)}). */
    private static int archColumn(BlockPos min, BlockPos max, Vec3 center, Vec3 v, boolean lowest) {
        double best = lowest ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    BlockPos corner = new BlockPos(min.getX() + dx * (max.getX() - min.getX()),
                            min.getY() + dy * (max.getY() - min.getY()),
                            min.getZ() + dz * (max.getZ() - min.getZ()));
                    double d = Vec3.atCenterOf(corner).subtract(center).dot(v);
                    best = lowest ? Math.min(best, d) : Math.max(best, d);
                }
            }
        }
        return (int) Math.round(best);
    }

    /**
     * Ghost preview of the elliptical ring the next LMB click will commit (ALT+E): the ring is
     * derived from the placed region and the block + face currently under the crosshair (the same
     * input the click sends, via {@link EllipseGeometry#regionEllipse}), and the voussoir band is
     * drawn in the clicked face's plane - a bright centerline and dim outer/inner edges (the
     * 0.5m radial offset along the ellipse normal), repeated for every depth layer of the wall.
     * Nothing is drawn when the current aim cannot form a ring, so the player sees exactly what
     * the click would generate before committing.
     */
    private static void renderEllipseGhost(PoseStack poseStack, VertexConsumer lines) {
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) {
            return;
        }
        EllipseGeometry.RegionEllipse re = EllipseGeometry.regionEllipse(
                EllipseState.regionMin(), EllipseState.regionMax(), bhr.getBlockPos(), bhr.getDirection());
        if (re == null) {
            return;
        }
        EllipseGeometry.EllipseResult e = re.ellipse();
        int bright = 0xFF5AFF8A;
        int dim = 0x805AFF8A;
        double[] thetas = e.thetas();
        for (int layer = 0; layer < re.layers(); layer++) {
            double layerOff = layer - (re.layers() - 1) / 2.0;
            Vec3 c = e.center().add(e.v().scale(layerOff));
            drawEllipseCurve(poseStack, lines, c, e.u(), e.w(), e.a(), e.b(), thetas, bright);
            drawEllipseBand(poseStack, lines, c, e.u(), e.w(), e.a(), e.b(), thetas, dim);
        }
    }

    /** Draws one closed ellipse curve (centerline at semi-axes {@code a}/{@code b}) sampled at
     *  the ring's actual wedge boundaries, so the preview shows the exact voussoir segmentation
     *  the commit makes. */
    private static void drawEllipseCurve(PoseStack poseStack, VertexConsumer lines, Vec3 c, Vec3 u, Vec3 w,
                                         double a, double b, double[] thetas, int color) {
        Vec3 prev = null;
        for (double t : thetas) {
            Vec3 p = c.add(u.scale(a * Math.cos(t))).add(w.scale(b * Math.sin(t)));
            if (prev != null) {
                drawLine(poseStack, lines, prev, p, color);
            }
            prev = p;
        }
    }

    /** Draws the outer and inner edges of the 1m-thick band: the centerline offset 0.5m along
     *  the outward ellipse normal ({@code (b cos t, a sin t)} normalized), which is exactly how
     *  the voussoir corners are placed. */
    private static void drawEllipseBand(PoseStack poseStack, VertexConsumer lines, Vec3 c, Vec3 u, Vec3 w,
                                        double a, double b, double[] thetas, int color) {
        Vec3 prevOuter = null;
        Vec3 prevInner = null;
        for (double t : thetas) {
            double nx = b * Math.cos(t);
            double ny = a * Math.sin(t);
            double len = Math.sqrt(nx * nx + ny * ny);
            Vec3 n = len < 1.0E-9 ? w : u.scale(nx / len).add(w.scale(ny / len));
            Vec3 p = c.add(u.scale(a * Math.cos(t))).add(w.scale(b * Math.sin(t)));
            Vec3 outer = p.add(n.scale(0.5));
            Vec3 inner = p.subtract(n.scale(0.5));
            if (prevOuter != null) {
                drawLine(poseStack, lines, prevOuter, outer, color);
                drawLine(poseStack, lines, prevInner, inner, color);
            }
            prevOuter = outer;
            prevInner = inner;
        }
    }

    /** Samples one circular arc in the u/w plane around origin {@code o} at radius {@code r},
     *  from angle {@code t0} down to {@code t1}, as line segments. */
    private static void drawArc(PoseStack poseStack, VertexConsumer lines, Vec3 o, Vec3 u, Vec3 w,
                                double r, double t0, double t1, int color) {
        int segments = 64;
        Vec3 prev = null;
        for (int i = 0; i <= segments; i++) {
            double t = t0 + (t1 - t0) * i / segments;
            Vec3 p = o.add(u.scale(r * Math.cos(t))).add(w.scale(r * Math.sin(t)));
            if (prev != null) {
                drawLine(poseStack, lines, prev, p, color);
            }
            prev = p;
        }
    }

    /** Draws a straight 3D line segment using the lines render type. */
    private static void drawLine(PoseStack poseStack, VertexConsumer lines, Vec3 from, Vec3 to, int color) {
        if (from.distanceToSqr(to) < 1.0E-6) {
            return;
        }
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        Vec3 dir = to.subtract(from).normalize();
        var pose = poseStack.last();
        lines.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
                .setColor(r, g, b, a)
                .setNormal(pose, (float) dir.x, (float) dir.y, (float) dir.z);
        lines.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
                .setColor(r, g, b, a)
                .setNormal(pose, (float) dir.x, (float) dir.y, (float) dir.z);
    }

    private static void drawMarkerCube(PoseStack poseStack, NeoForgeRenderBuffer buffers, BlockPos pos) {
        AABB marker = cornerMarker(pos);
        NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, marker, 1.0f, 0.79f, 0.3f, 0.6f);
        drawBox(poseStack, buffers, marker, CORNER_COLOR);
    }

    private static void drawCornerMarker(PoseStack poseStack, NeoForgeRenderBuffer buffers, BlockPos pos) {
        AABB marker = cornerMarker(pos);
        // Filled gold cube + wireframe edge.
        NeoForgeRenderBuffer.renderFilledBox(poseStack, buffers, marker, 1.0f, 0.79f, 0.3f, 0.55f);
        drawBox(poseStack, buffers, marker, CORNER_COLOR);
    }

    private static AABB cornerMarker(BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double size = 0.45;
        return new AABB(center.x - size / 2, center.y - size / 2, center.z - size / 2,
                center.x + size / 2, center.y + size / 2, center.z + size / 2);
    }

    private static void drawBox(PoseStack poseStack, NeoForgeRenderBuffer buffers, AABB box, int color) {
        NeoForgeRenderBuffer.renderLineBox(poseStack, buffers, box, color);
    }
}
