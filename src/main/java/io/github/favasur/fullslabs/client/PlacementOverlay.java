package io.github.favasur.fullslabs.client;

import io.github.favasur.fullslabs.block.SlabVertical;
import io.github.favasur.fullslabs.config.Config;
import io.github.favasur.fullslabs.config.Controls;
import io.github.favasur.fullslabs.util.RotatedSlabPlacement;
import io.github.favasur.fullslabs.util.SlabPlacement;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Screen-space "slab-placement overlay": while the player holds a slab item, the volume the
 * would-be placed slab occupies (against the targeted block face, honoring the graft's placement
 * rules) is projected onto the HUD and highlighted with a semi-transparent fill plus a colored
 * edge. Colors come from {@link Config}; the overlay can be toggled through {@link Controls}.
 *
 * <p>Drawing goes through {@link OverlayDraw}, a tiny loader-neutral surface each loader maps
 * onto its own GUI API ({@code GuiGraphics} on 1.21.1, {@code GuiGraphicsExtractor} on 26.2).
 * The 1.21.1 projection uses the actual rendered fov cached by
 * {@code io.github.favasur.fullslabs.mixin.client.GameRendererMixin}.
 */
public final class PlacementOverlay {

    /** Cached by the 1.21.1 GameRendererMixin from the real rendered fov; -1 until first frame. */
    public static float lastFov = -1.0f;

    /** Minimal drawing surface; each loader maps it onto its own GUI drawing API. */
    public interface OverlayDraw {
        int width();

        int height();

        void fill(int x1, int y1, int x2, int y2, int color);

        void hLine(int x1, int x2, int y, int color);

        void vLine(int x1, int y1, int y2, int color);
    }

    private PlacementOverlay() {
    }

    public static void render(OverlayDraw draw, Minecraft mc) {
        if (!Controls.isOverlayActive() || mc.player == null || mc.level == null) {
            return;
        }
        Config.load();
        if (!(mc.hitResult instanceof BlockHitResult hit)) {
            return;
        }
        SlabBlock slab = heldSlab(mc.player);
        if (slab == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        // A rotated block under the cursor places a rotated slab against its face; the volume is
        // an oriented box, not a world-aligned AABB.
        Oriented oriented = rotatedVolume(mc, slab, pos, hit);
        AABB volume = oriented == null ? targetVolume(mc, slab, mc.level.getBlockState(pos), pos, hit) : null;
        if (oriented == null && volume == null) {
            return;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vector2f p;
            if (oriented != null) {
                double lx = (i & 1) == 0 ? oriented.localBounds().minX : oriented.localBounds().maxX;
                double ly = (i & 2) == 0 ? oriented.localBounds().minY : oriented.localBounds().maxY;
                double lz = (i & 4) == 0 ? oriented.localBounds().minZ : oriented.localBounds().maxZ;
                Vector3f corner = oriented.rotation().transform(new Vector3f(
                        (float) (lx - 0.5), (float) (ly - 0.5), (float) (lz - 0.5)), new Vector3f());
                p = project(draw, mc,
                        oriented.center().x + corner.x,
                        oriented.center().y + corner.y,
                        oriented.center().z + corner.z);
            } else {
                p = project(draw, mc,
                        (i & 1) == 0 ? volume.minX : volume.maxX,
                        (i & 2) == 0 ? volume.minY : volume.maxY,
                        (i & 4) == 0 ? volume.minZ : volume.maxZ);
            }
            if (p == null) {
                return; // part of the volume is behind the camera
            }
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
        }
        int x1 = (int) Math.floor(minX);
        int y1 = (int) Math.floor(minY);
        int x2 = (int) Math.ceil(maxX);
        int y2 = (int) Math.ceil(maxY);
        if (x2 <= x1 || y2 <= y1) {
            return;
        }
        draw.fill(x1, y1, x2, y2, Config.fillColor);
        draw.hLine(x1, x2, y1, Config.edgeColor);
        draw.hLine(x1, x2, y2, Config.edgeColor);
        draw.vLine(x1, y1, y2, Config.edgeColor);
        draw.vLine(x2, y1, y2, Config.edgeColor);
    }

    private static SlabBlock heldSlab(Player player) {
        for (ItemStack stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof SlabBlock slab) {
                return slab;
            }
        }
        return null;
    }

    /**
     * The AABB (in world space) of the slab volume the current click would place. A click on a
     * same-material slab merges it into a full double slab (unless it is a vertical slab clicked
     * from outside its volume, which places a new vertical slab against the clicked side);
     * otherwise the new slab lands in the clicked block if replaceable, or in the block across
     * the clicked face, standing per the placement mode.
     */
    private static AABB targetVolume(Minecraft mc, SlabBlock slab, BlockState clicked, BlockPos pos, BlockHitResult hit) {
        if (clicked.getBlock() == slab) {
            if (SlabVertical.isVertical(clicked) && !SlabVertical.isInsideSlab(clicked, pos, hit.getLocation())) {
                return placedVolume(mc, slab, clicked, pos, hit);
            }
            return new AABB(pos);
        }
        return placedVolume(mc, slab, clicked, pos, hit);
    }

    /**
     * The oriented volume of a slab placed against a Builder Tools rotated block: the landing box
     * (rotated with the block, centered half a block off the clicked face) clipped to the placed
     * slab's shape. Null when nothing is under the cursor or the landing cell is blocked.
     */
    private static Oriented rotatedVolume(Minecraft mc, SlabBlock slab, BlockPos pos, BlockHitResult hit) {
        RotatedBlockLookup lookup = RotatedBlockLookup.get();
        if (lookup == null) {
            return null;
        }
        RotatedBlockLookup.Target target = lookup.at(mc.level, pos);
        if (target == null) {
            return null;
        }
        Quaternionf rotation = RotatedSlabPlacement.rotation(target.yaw(), target.pitch());
        // Same-material merge (mirrors the vanilla vertical-slab rule): clicking the inner face
        // of a rotated vertical slab fills the block - preview the full double slab in place at
        // the block's own center.
        RotatedSlabPlacement.LocalClick click = RotatedSlabPlacement.localClick(
                rotation, target.shapeBounds(), target.center(), hit.getLocation(), mc.player.getDirection());
        if (target.state().getBlock() == slab
                && SlabVertical.isVertical(target.state())
                && SlabVertical.isInsideSlab(target.state(), BlockPos.ZERO, click.localHit())) {
            return new Oriented(target.center(), rotation, new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0));
        }
        RotatedSlabPlacement.Result result = RotatedSlabPlacement.place(
                Controls.getPlacementMode(mc.player.getUUID()), rotation, target.shapeBounds(),
                target.center(), hit.getLocation(), mc.player.getDirection());
        BlockPos landing = BlockPos.containing(result.boxCenter());
        // The click path re-places in place when the landing box rounds back into the clicked
        // block's own cell (diagonal rotations), but refuses cells occupied by OTHER rotated
        // blocks - mirror that so the preview never shows a placement the click would reject.
        if (RotatedBlockLookup.get() != null
                && !landing.equals(pos)
                && RotatedBlockLookup.get().at(mc.level, landing) != null) {
            return null;
        }
        if (!mc.level.getBlockState(landing).canBeReplaced()) {
            return null;
        }
        BlockState state = SlabVertical.applyDirection(slab.defaultBlockState(), result.target());
        AABB local = state.getCollisionShape(mc.level, BlockPos.containing(result.boxCenter())).bounds();
        if (local == null || local.getSize() < 1.0E-4) {
            return null;
        }
        return new Oriented(result.boxCenter(), rotation, local);
    }

    /** An oriented box: world center, rotation and local 0..1 shape bounds. */
    private record Oriented(Vec3 center, Quaternionf rotation, AABB localBounds) {
    }

    private static AABB placedVolume(Minecraft mc, SlabBlock slab, BlockState clicked, BlockPos pos, BlockHitResult hit) {
        BlockPos landing = clicked.canBeReplaced() ? pos : pos.relative(hit.getDirection());
        if (!mc.level.getBlockState(landing).canBeReplaced()) {
            return null;
        }
        // The click geometry is evaluated against the clicked block (identical to the placement
        // mixin's BlockPlaceContext), so the overlay and the placed slab always agree.
        Direction target = SlabPlacement.getTargetedDirection(
                Controls.getPlacementMode(mc.player.getUUID()), hit.getDirection(),
                mc.player.getDirection(), pos, hit.getLocation());
        BlockState result = SlabVertical.getTargetedState(slab, hit.getDirection(), target, mc.player.getYRot());
        AABB aabb;
        if (SlabVertical.isVertical(result)) {
            aabb = SlabVertical.shape(result).bounds();
        } else {
            aabb = switch (result.getValue(BlockStateProperties.SLAB_TYPE)) {
                case TOP -> new AABB(0.0, 0.5, 0.0, 1.0, 1.0, 1.0);
                case DOUBLE -> new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
                default -> new AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
            };
        }
        return aabb.move(landing);
    }

    /** Projects a world-space point to scaled GUI coordinates; null when behind the camera. */
    private static Vector2f project(OverlayDraw draw, Minecraft mc, double x, double y, double z) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vector3f rel = new Vector3f((float) x, (float) y, (float) z).sub(camera.getPosition().toVector3f());
        rel.rotate(camera.rotation().conjugate());
        float fov = lastFov > 0.0f ? lastFov : mc.options.fov().get().floatValue();
        float aspect = (float) draw.width() / (float) draw.height();
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 1000.0f);
        Vector4f clip = new Vector4f(rel, 1.0f);
        proj.transform(clip);
        if (clip.w <= 0.0f) {
            return null;
        }
        float invW = 1.0f / clip.w;
        return new Vector2f(
                (clip.x * invW * 0.5f + 0.5f) * (float) draw.width(),
                (0.5f - clip.y * invW * 0.5f) * (float) draw.height());
    }
}
