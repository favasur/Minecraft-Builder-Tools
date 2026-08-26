package net.buildertools.client;

import net.buildertools.entity.OffGridBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Progressive mining of an off-grid block (survival): instead of breaking on the first hit like a
 * painting, holding the left mouse button on the block accumulates digging progress at the
 * vanilla block's destroy speed (hardness, tool, effects). Progress resets when the button is
 * released or the cursor leaves the block - exactly like mining a normal block. When the bar
 * fills, {@link #tick} returns true and the caller sends the removal packet (the server drops the
 * block's item in survival).
 */
@OnlyIn(Dist.CLIENT)
public final class OffGridMining {
    private static OffGridBlockEntity target;
    private static float progress;

    private OffGridMining() {
    }

    public static void start(OffGridBlockEntity block) {
        target = block;
        progress = 0.0f;
    }

    public static void stop() {
        target = null;
        progress = 0.0f;
    }

    public static boolean isActive() {
        return target != null && !target.isRemoved();
    }

    /** The block currently being mined (for the crack overlay). */
    public static OffGridBlockEntity getTarget() {
        return target;
    }

    /** Mining progress 0..1 (for the crack overlay). */
    public static float getProgress() {
        return progress;
    }

    /**
     * Advances the dig progress for the current tick. Returns true when the block is fully mined
     * (caller removes it). Stops/resets automatically when the button is released, the cursor
     * leaves the block, or the block disappears.
     */
    public static boolean tick(Player player) {
        if (!isActive()) {
            stop();
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow() == null) {
            stop();
            return false;
        }
        // Vanilla mining resets when the button is released or you look away.
        if (GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
                != GLFW.GLFW_PRESS) {
            stop();
            return false;
        }
        OffGridBlockEntity hit = raycastOffGridBlock(player, 6.0);
        if (hit != target) {
            stop();
            return false;
        }
        BlockState state = target.getRepresentedState();
        BlockPos pos = BlockPos.containing(target.modelCenter());
        progress += state.getDestroyProgress(player, player.level(), pos);
        if (progress >= 1.0f) {
            stop();
            return true;
        }
        return false;
    }

    /** The off-grid block under the cursor, or null. */
    private static OffGridBlockEntity raycastOffGridBlock(Player player, double reach) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        Vec3 end = eye.add(dir.scale(reach));
        double best = Double.MAX_VALUE;
        OffGridBlockEntity bestHit = null;
        for (OffGridBlockEntity block : minecraft.level.getEntitiesOfClass(OffGridBlockEntity.class,
                player.getBoundingBox().expandTowards(dir.scale(reach)).inflate(1.5))) {
            Vec3 center = block.modelCenter();
            Quaternionf rot = net.buildertools.util.OffGridTransform.rotation(
                    block.getPlacementYaw(), block.getPlacementPitch());
            Quaternionf inv = rot.conjugate();
            Vector3f o = inv.transform(new Vector3f(
                    (float) (eye.x - center.x), (float) (eye.y - center.y), (float) (eye.z - center.z)),
                    new Vector3f());
            Vector3f d = inv.transform(new Vector3f(
                    (float) dir.x, (float) dir.y, (float) dir.z), new Vector3f());
            AABB shape = stateBounds(block);
            double minX = shape.minX - 0.5, maxX = shape.maxX - 0.5;
            double minY = shape.minY - 0.5, maxY = shape.maxY - 0.5;
            double minZ = shape.minZ - 0.5, maxZ = shape.maxZ - 0.5;
            double tmin = 0.0, tmax = reach;
            boolean missed = false;
            for (int i = 0; i < 3; i++) {
                double oi = i == 0 ? o.x : i == 1 ? o.y : o.z;
                double di = i == 0 ? d.x : i == 1 ? d.y : d.z;
                double lo = i == 0 ? minX : i == 1 ? minY : minZ;
                double hi = i == 0 ? maxX : i == 1 ? maxY : maxZ;
                if (Math.abs(di) < 1.0E-8) {
                    if (oi < lo || oi > hi) {
                        missed = true;
                        break;
                    }
                    continue;
                }
                double tLow = (lo - oi) / di;
                double tHigh = (hi - oi) / di;
                if (tLow > tHigh) {
                    double tmp = tLow;
                    tLow = tHigh;
                    tHigh = tmp;
                }
                if (tLow > tmin) {
                    tmin = tLow;
                }
                if (tHigh < tmax) {
                    tmax = tHigh;
                }
                if (tmin > tmax) {
                    missed = true;
                    break;
                }
            }
            if (missed) {
                continue;
            }
            double dist = eye.distanceToSqr(eye.add(dir.scale(tmin)));
            if (dist < best) {
                best = dist;
                bestHit = block;
            }
        }
        return bestHit;
    }

    /** The represented block's shape bounds in block-local 0..1 space. */
    private static AABB stateBounds(OffGridBlockEntity block) {
        Minecraft minecraft = Minecraft.getInstance();
        return block.getRepresentedState().getCollisionShape(minecraft.level, BlockPos.ZERO).bounds();
    }
}
