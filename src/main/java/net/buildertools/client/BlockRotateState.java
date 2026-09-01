package net.buildertools.client;

import net.buildertools.client.settings.BuilderSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * Off-grid placement preview (Hytale-style free placement): press R while holding a block to
 * enter the mode. The preview block floats FREELY - it follows the cursor ray continuously and
 * never snaps to the vanilla XYZ grid: aiming at a surface puts it flush against that surface
 * at the exact cursor point, aiming at air floats it at the air-place distance. Hold the left
 * mouse button and move the mouse to rotate the block (horizontal movement spins the yaw,
 * vertical movement tilts the pitch) - the position locks while rotating so the block spins
 * strictly in place, and releasing the button lets the preview follow the cursor again. Press R
 * again to cancel, right-click or Enter to place the block at the current position and rotation.
 * Aiming at an already-placed off-grid block and pressing R re-enters the editor for that block
 * so it can be re-rotated in place.
 */
@OnlyIn(Dist.CLIENT)
public final class BlockRotateState {
    private static boolean active;
    private static BlockPos target;       // the grid cell the placement is anchored to (containing(center))
    private static Vec3 previewCenter;    // the free-following model center of the preview block
    private static boolean fixedTarget;   // true while re-rotating a placed block in place
    private static Vec3 fixedCenter;      // the placed block's (fractional) model center, when re-rotating
    private static BlockState previewState; // block shown in the preview (null = use held item)
    private static float yaw;
    private static float pitch;
    private static boolean billboard;   // true = the block always faces the player (Hytale billboard)
    private static double lastAngle;
    private static double lastPitch;

    private BlockRotateState() {
    }

    public static boolean isActive() {
        return active;
    }

    public static BlockPos getTarget() {
        return target;
    }

    public static float getYawDeg() {
        return yaw;
    }

    public static float getPitchDeg() {
        return pitch;
    }

    /** True when the preview block is billboarded (always faces the player). */
    public static boolean isBillboard() {
        return billboard;
    }

    /** Toggles the player-facing billboard mode on/off. */
    public static void toggleBillboard() {
        billboard = !billboard;
    }

    /** The block to render in the preview; null means "use the held item". */
    public static BlockState getPreviewState() {
        return previewState;
    }

    /** True while re-rotating an existing off-grid block (position stays fixed). */
    public static boolean isFixedTarget() {
        return fixedTarget;
    }

    /** The world-space center the placed block will have: the fixed model center when
     *  re-rotating a placed block, otherwise the cursor-following free position. */
    public static Vec3 getCenter() {
        return fixedCenter != null ? fixedCenter : previewCenter;
    }

    /** The fixed model center when re-rotating a placed block, else null. */
    public static Vec3 getFixedCenter() {
        return fixedCenter;
    }

    /** Enters placement-preview mode: the block follows the cursor freely, starting unrotated. */
    public static void start(Player player) {
        yaw = 0.0f;
        pitch = 0.0f;
        billboard = false;
        fixedTarget = false;
        fixedCenter = null;
        previewState = null;
        previewCenter = computePreviewCenter(player);
        target = BlockPos.containing(previewCenter);
        captureAngles(player);
        active = true;
    }

    /** Enters re-rotation mode for an existing off-grid block in {@code cell}, keeping its
     *  (possibly fractional) model center fixed so it spins strictly in place. */
    public static void start(Player player, BlockPos cell, Vec3 center, float baseYaw, float basePitch, BlockState state) {
        target = cell;
        fixedCenter = center;
        yaw = baseYaw;
        pitch = basePitch;
        billboard = false; // re-rotating a placed block always starts a manual (non-billboard) spin
        fixedTarget = true;
        previewState = state;
        captureAngles(player);
        active = true;
    }

    public static void stop() {
        active = false;
        target = null;
        previewCenter = null;
        fixedTarget = false;
        fixedCenter = null;
        previewState = null;
        yaw = 0.0f;
        pitch = 0.0f;
        billboard = false;
    }

    /**
     * Follows the cursor each tick. While the left button is held (actively rotating) the
     * position stays LOCKED at the spot where the rotation started, so the block spins strictly
     * in place - it never slides while its angle is being changed. Releasing the button lets the
     * preview follow the cursor again for re-aiming.
     */
    public static void update(Player player) {
        if (!active) {
            return;
        }
        if (!fixedTarget && !isLeftMouseDown()) {
            previewCenter = computePreviewCenter(player);
            target = BlockPos.containing(previewCenter);
        }
        if (target == null) {
            return;
        }
        Vec3 center = getCenter();
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        double t = 0.0;
        if (Math.abs(dir.y) > 1.0E-5) {
            t = (center.y - eye.y) / dir.y;
            if (t < 0) {
                t = 0;
            }
        }
        Vec3 hit = eye.add(dir.scale(t));

        double angle = Math.atan2(hit.z - center.z, hit.x - center.x);
        double d = angle - lastAngle;
        double dx = hit.x - center.x;
        double dz = hit.z - center.z;
        double pitchAngle = Math.atan2(hit.y - center.y, Math.sqrt(dx * dx + dz * dz));
        double dp = pitchAngle - lastPitch;
        if (isLeftMouseDown() && !billboard) {
            // Rotate only while the left mouse button is held (drag to rotate, like Hytale). The
            // baselines refresh every tick, so releasing and re-pressing never causes a jump.
            // Wrap the step so crossing +/-180 deg never snaps the block around. In billboard
            // mode the block always faces the player instead, so dragging is ignored.
            yaw += (float) Math.toDegrees(Math.atan2(Math.sin(d), Math.cos(d)));
            pitch += (float) Math.toDegrees(Math.atan2(Math.sin(dp), Math.cos(dp)));
        }
        lastAngle = angle;
        lastPitch = pitchAngle;
    }

    /**
     * Yaw/pitch (degrees) that make the block's +Z face the player's eye, matching what the
     * placed display's CENTER billboard constraint will render - used by the preview while the
     * block is billboarded so it shows exactly what will be placed.
     */
    public static float[] facingAngles(Player player, Vec3 center) {
        Vec3 eye = player.getEyePosition(1.0f);
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4)));
        return new float[]{yaw, pitch};
    }

    /**
     * The free placement center for the cursor: the block hit point nudged flush against the
     * face (the block's surface exactly at the cursor point) when aiming at a block, otherwise
     * the air-place distance along the look ray. Fully off-grid - the block never snaps to the
     * vanilla XYZ grid.
     */
    public static Vec3 computePreviewCenter(Player player) {
        float reach = Math.max(BuilderSettings.getAirPlaceDistance(), 5.0f);
        HitResult hit = player.pick(reach, 1.0f, false);
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bhr) {
            Vec3 normal = new Vec3(bhr.getDirection().getStepX(),
                    bhr.getDirection().getStepY(), bhr.getDirection().getStepZ());
            float effYaw = yaw;
            float effPitch = pitch;
            if (billboard) {
                float[] facing = facingAngles(player, hit.getLocation());
                effYaw = facing[0];
                effPitch = facing[1];
            }
            return hit.getLocation().add(normal.scale(halfExtentAlong(player, effYaw, effPitch, normal)));
        }
        return eye.add(dir.scale(BuilderSettings.getAirPlaceDistance()));
    }

    /** The block shown in the preview: the fixed re-rotate state or the held block. */
    private static BlockState previewBlockState(Player player) {
        if (previewState != null) {
            return previewState;
        }
        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof BlockItem blockItem) {
            return io.github.favasur.fullslabs.block.SlabVertical.vertical(
                    blockItem.getBlock().defaultBlockState());
        }
        return null;
    }

    /** Half-extent of the (rotated) preview block's collision box along a world direction: the
     *  nudge that makes the block sit flush against the clicked surface. */
    private static double halfExtentAlong(Player player, float yaw, float pitch, Vec3 normal) {
        BlockState state = previewBlockState(player);
        AABB box = state != null
                ? state.getCollisionShape(player.level(), BlockPos.ZERO).bounds()
                : AABB.unitCubeFromLowerCorner(Vec3.ZERO);
        org.joml.Quaternionf rot = net.buildertools.util.OffGridTransform.rotation(yaw, pitch);
        org.joml.Vector3f n = new org.joml.Vector3f((float) normal.x, (float) normal.y, (float) normal.z);
        double ex = Math.abs(rot.transform(new org.joml.Vector3f((float) (box.getXsize() * 0.5), 0, 0), new org.joml.Vector3f()).dot(n));
        double ey = Math.abs(rot.transform(new org.joml.Vector3f(0, (float) (box.getYsize() * 0.5), 0), new org.joml.Vector3f()).dot(n));
        double ez = Math.abs(rot.transform(new org.joml.Vector3f(0, 0, (float) (box.getZsize() * 0.5)), new org.joml.Vector3f()).dot(n));
        return ex + ey + ez;
    }

    /** The real left-button state, since the vanilla key mapping is bypassed while previewing. */
    private static boolean isLeftMouseDown() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow() == null) {
            return false;
        }
        return GLFW.glfwGetMouseButton(
                minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    /** The cell a newly placed block would occupy (from the player's pick), or null. */
    public static BlockPos placementCell(Player player) {
        HitResult hit = player.pick(5.0, 1.0f, false);
        if (hit instanceof BlockHitResult blockHit) {
            return blockHit.getBlockPos().relative(blockHit.getDirection());
        }
        return null;
    }

    /** Records the cursor's horizontal and vertical angle around the target as the zero point. */
    private static void captureAngles(Player player) {
        if (target == null) {
            lastAngle = 0.0;
            lastPitch = 0.0;
            return;
        }
        Vec3 center = getCenter();
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        double t = 0.0;
        if (Math.abs(dir.y) > 1.0E-5) {
            t = (center.y - eye.y) / dir.y;
            if (t < 0) {
                t = 0;
            }
        }
        Vec3 hit = eye.add(dir.scale(t));
        lastAngle = Math.atan2(hit.z - center.z, hit.x - center.x);
        double dx = hit.x - center.x;
        double dz = hit.z - center.z;
        lastPitch = Math.atan2(hit.y - center.y, Math.sqrt(dx * dx + dz * dz));
    }
}
