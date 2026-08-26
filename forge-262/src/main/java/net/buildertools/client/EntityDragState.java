package net.buildertools.client;

import net.buildertools.client.settings.BuilderSettings;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.network.packet.EntityTransformPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The "free move" entity drag: with an entity selected, hold the right mouse button on it and
 * move the mouse — the entity follows the cursor along a horizontal plane. "Lock to Surface" keeps
 * it grounded; "Grid Snap" snaps its position to the configured grid size.
 */
@OnlyIn(Dist.CLIENT)
public final class EntityDragState {
    private static Entity entity;
    private static double planeY;
    private static Vec3 lastSent;

    private EntityDragState() {
    }

    public static boolean isDragging() {
        return entity != null && !entity.isRemoved();
    }

    public static void start(Entity target) {
        entity = target;
        // Off-grid blocks move from their MODEL center (the point the server stores and the
        // visual/collision derive from); regular entities use their position.
        planeY = target instanceof OffGridBlockEntity og ? og.modelCenter().y : target.getY();
        lastSent = null;
    }

    public static void stop() {
        entity = null;
        lastSent = null;
    }

    public static void update(Player player) {
        if (!isDragging()) {
            return;
        }
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        if (Math.abs(dir.y) < 1.0E-5) {
            return;
        }
        double t = (planeY - eye.y) / dir.y;
        if (t < 0) {
            return;
        }
        Vec3 target = eye.add(dir.scale(t));

        double x = target.x;
        double z = target.z;
        // Off-grid blocks drag by their model center; regular entities by their position.
        double y = entity instanceof OffGridBlockEntity og ? og.modelCenter().y : entity.getY();

        if (BuilderSettings.isGridSnap()) {
            double s = BuilderSettings.getGridSize();
            if (s > 0.001) {
                x = Math.floor(x / s + 0.5) * s;
                z = Math.floor(z / s + 0.5) * s;
            }
        }
        if (BuilderSettings.isSurfaceLock()) {
            BlockPos pos = BlockPos.containing(x, y, z);
            int ground = entity.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY();
            y = ground + 1.0;
        } else if (BuilderSettings.isGridSnap()) {
            double s = BuilderSettings.getGridSize();
            if (s > 0.001) {
                y = Math.floor(y / s + 0.5) * s;
            }
        }

        Vec3 next = new Vec3(x, y, z);
        if (lastSent != null && lastSent.distanceToSqr(next) < 1.0E-6) {
            return;
        }
        lastSent = next;
        ClientPackets.sendToServer(new EntityTransformPacket(
                entity.getId(), x, y, z, entity.getYRot(), entity.getXRot(), false));
    }
}
