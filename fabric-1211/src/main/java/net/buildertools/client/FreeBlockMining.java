package net.buildertools.client;

import net.buildertools.server.RotationStore;
import net.buildertools.util.RotationData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/**
 * Progressive mining of a rotated block (survival): holding the left mouse button on the block
 * accumulates digging progress at the vanilla block's destroy speed (hardness, tool, effects).
 * Progress resets when the button is released or the cursor leaves the block - exactly like
 * mining a normal block. When the bar fills, {@link #tick} returns true and the caller sends the
 * break packet (the server drops the block's item in survival).
 */
public final class FreeBlockMining {
    private static BlockPos target;
    private static float progress;

    private FreeBlockMining() {
    }

    public static void start(BlockPos cell) {
        target = cell.immutable();
        progress = 0.0f;
    }

    public static void stop() {
        target = null;
        progress = 0.0f;
    }

    public static boolean isActive() {
        return target != null;
    }

    /** The cell being mined (for the crack overlay). */
    public static BlockPos getTarget() {
        return target;
    }

    /** Mining progress 0..1 (for the crack overlay). */
    public static float getProgress() {
        return progress;
    }

    /**
     * Advances the dig progress for the current tick. Returns true when the block is fully mined
     * (caller removes it). Stops/resets automatically when the button is released or the cursor
     * leaves the block.
     */
    public static boolean tick(Player player) {
        if (target == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow() == null) {
            stop();
            return false;
        }
        if (GLFW.glfwGetMouseButton(minecraft.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
                != GLFW.GLFW_PRESS) {
            stop();
            return false;
        }
        BlockPos aimed = aimedFreeBlock(player);
        if (aimed == null || !aimed.equals(target)) {
            stop();
            return false;
        }
        RotationData rot = RotationStore.get(player.level(), target);
        if (rot == null) {
            stop();
            return false;
        }
        progress += rot.state().getDestroyProgress(player, player.level(), target);
        if (progress >= 1.0f) {
            stop();
            return true;
        }
        return false;
    }

    /** The free-block cell under the cursor (via the mod's raycast mixin), or null. */
    private static BlockPos aimedFreeBlock(Player player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hitResult != null && minecraft.hitResult.getType() == HitResult.Type.BLOCK
                && minecraft.hitResult instanceof BlockHitResult bhr
                && RotationStore.hasRotation(player.level(), bhr.getBlockPos())) {
            return bhr.getBlockPos();
        }
        return null;
    }
}
