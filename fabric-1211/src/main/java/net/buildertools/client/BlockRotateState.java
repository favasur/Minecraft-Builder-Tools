package net.buildertools.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Off-grid placement preview (Hytale-style offset placement): press R while holding a block to
 * enter the mode, then hold the left mouse button and move the mouse to rotate the block around
 * its cell - horizontal movement spins the yaw, vertical movement tilts the pitch, releasing the
 * button freezes the angle. Press R again to cancel, right-click or Enter to place the block at
 * the current rotation. Aiming at an already-placed off-grid block and pressing R re-enters the
 * editor for that block so it can be re-rotated in place.
 */
public final class BlockRotateState {
    private static boolean active;
    private static BlockPos target;
    private static boolean fixedTarget;   // true while re-rotating a placed block in place
    private static BlockState previewState; // block shown in the preview (null = use held item)
    private static float yaw;
    private static float pitch;
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

    /** The block to render in the preview; null means "use the held item". */
    public static BlockState getPreviewState() {
        return previewState;
    }

    /** True while re-rotating an existing off-grid block (target cell stays fixed). */
    public static boolean isFixedTarget() {
        return fixedTarget;
    }

    /** Enters placement-preview mode: the cell follows the cursor, starting unrotated. */
    public static void start(Player player) {
        target = placementCell(player);
        yaw = 0.0f;
        pitch = 0.0f;
        fixedTarget = false;
        previewState = null;
        captureAngles(player);
        active = true;
    }

    /** Enters re-rotation mode for an existing off-grid block in {@code cell}. */
    public static void start(Player player, BlockPos cell, float baseYaw, float basePitch, BlockState state) {
        target = cell;
        yaw = baseYaw;
        pitch = basePitch;
        fixedTarget = true;
        previewState = state;
        captureAngles(player);
        active = true;
    }

    public static void stop() {
        active = false;
        target = null;
        fixedTarget = false;
        previewState = null;
        yaw = 0.0f;
        pitch = 0.0f;
    }

    /** Follows the cursor around the preview cell each tick; keeps the target cell in sync. */
    public static void update(Player player) {
        if (!active) {
            return;
        }
        if (!fixedTarget) {
            BlockPos cell = placementCell(player);
            if (cell != null) {
                target = cell;
            }
        }
        if (target == null) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(target);
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
        if (isLeftMouseDown()) {
            // Rotate only while the left mouse button is held (drag to rotate, like Hytale). The
            // baselines refresh every tick, so releasing and re-pressing never causes a jump.
            // Wrap the step so crossing +/-180 deg never snaps the block around.
            yaw += (float) Math.toDegrees(Math.atan2(Math.sin(d), Math.cos(d)));
            pitch += (float) Math.toDegrees(Math.atan2(Math.sin(dp), Math.cos(dp)));
        }
        lastAngle = angle;
        lastPitch = pitchAngle;
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
        Vec3 center = Vec3.atCenterOf(target);
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
