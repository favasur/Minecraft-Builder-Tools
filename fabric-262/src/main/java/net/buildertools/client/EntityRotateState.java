package net.buildertools.client;

import net.buildertools.network.packet.EntityTransformPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Hytale-style rotate mode for the Entity Tool: press R to enter, then move the mouse - the
 * selected entity's yaw follows the cursor around it. Press Alt while entering to rotate only
 * the head instead of the whole body. Press R again (or right-click) to confirm and leave.
 */
@OnlyIn(Dist.CLIENT)
public final class EntityRotateState {
    private static Entity entity;
    private static boolean headMode;
    private static float baseYaw;
    private static double angleDelta;
    private static double lastAngle;

    private EntityRotateState() {
    }

    public static boolean isActive() {
        return entity != null && !entity.isRemoved();
    }

    public static boolean isHeadMode() {
        return headMode;
    }

    public static Entity getEntity() {
        return entity;
    }

    public static void start(Entity target, boolean head) {
        entity = target;
        headMode = head;
        baseYaw = head ? target.getYHeadRot() : target.getYRot();
        angleDelta = 0.0;
        lastAngle = rayAngle(Minecraft.getInstance().player);
    }

    public static void stop() {
        entity = null;
        angleDelta = 0.0;
    }

    /** Recomputes the cursor angle around the entity and rotates it to follow the mouse. */
    public static void update(Player player) {
        if (!isActive()) {
            return;
        }
        double angle = rayAngle(player);
        double d = angle - lastAngle;
        // Wrap the step so crossing the +/-180 deg boundary never snaps the entity around.
        angleDelta += Math.atan2(Math.sin(d), Math.cos(d));
        lastAngle = angle;

        float yaw = baseYaw + (float) Math.toDegrees(angleDelta);
        ClientPackets.sendToServer(new EntityTransformPacket(
                entity.getId(), entity.getX(), entity.getY(), entity.getZ(),
                yaw, entity.getXRot(), headMode));
    }

    /** Horizontal angle (radians) of the player's eye ray around the entity's position. */
    private static double rayAngle(Player player) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        double t = 0.0;
        if (Math.abs(dir.y) > 1.0E-5) {
            t = (entity.getY() - eye.y) / dir.y;
            if (t < 0) {
                t = 0;
            }
        }
        Vec3 hit = eye.add(dir.scale(t));
        return Math.atan2(hit.z - entity.getZ(), hit.x - entity.getX());
    }
}
