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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

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

    /** Wires this renderer onto the mod's level-submission event (fired once per frame). */
    public static void register() {
        ForgeLevelRenderEvent.BUS.addListener(SelectionRenderer::onRenderLevel);
    }

    public static void onRenderLevel(ForgeLevelRenderEvent event) {
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
