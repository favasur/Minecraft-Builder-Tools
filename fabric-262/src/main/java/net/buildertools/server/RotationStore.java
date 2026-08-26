package net.buildertools.server;

import net.buildertools.network.packet.RotationSyncPacket;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mod's block layer (the "neighbor-dependent grid"). The server side is the persisted
 * {@link RotationSavedData}; the client side is a mirror kept in sync by
 * {@link RotationSyncPacket}s, so the renderer, collision and raycast code can read the rotated
 * blocks on both sides. A rotated block lives in its vanilla cell (which stays AIR) - the layer
 * entry carries the block's real state and its yaw/pitch. Reading is a map lookup; writing is
 * server-authoritative and broadcasts the change.
 */
public final class RotationStore {
    private static final Map<BlockPos, RotationData> CLIENT = new ConcurrentHashMap<>();

    private RotationStore() {
    }

    /** The rotated block in the cell, or null when the cell holds no mod-layer block. */
    public static RotationData get(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            return RotationSavedData.of(serverLevel).get(pos);
        }
        return CLIENT.get(pos);
    }

    public static boolean hasRotation(BlockGetter level, BlockPos pos) {
        return get(level, pos) != null;
    }

    /** Sets (or updates) the rotated block in a cell and pushes it to every client. */
    public static void set(ServerLevel level, BlockPos pos, RotationData data) {
        Vec3 c = data.center(pos);
        System.out.println("[BuilderTools] RotationStore.set " + pos + " center=(" + c.x + "," + c.y + "," + c.z
                + ") yaw=" + data.yaw() + " pitch=" + data.pitch());
        RotationSavedData.of(level).set(pos, data);
        broadcast(level, new RotationSyncPacket(pos, data.state(), data.yaw(), data.pitch(), data.billboard(), false,
                c.x, c.y, c.z));
    }

    /** Removes the rotated block from a cell (its block is being broken or replaced). */
    public static void remove(ServerLevel level, BlockPos pos) {
        if (RotationSavedData.of(level).remove(pos)) {
            broadcast(level, new RotationSyncPacket(pos, null, 0.0f, 0.0f, false, true, 0.0, 0.0, 0.0));
        }
    }

    /** Sends the whole layer to a player (on world join). */
    public static void syncAllTo(ServerPlayer player) {
        for (Map.Entry<BlockPos, RotationData> e : RotationSavedData.of((ServerLevel) player.level()).all().entrySet()) {
            Vec3 c = e.getValue().center(e.getKey());
            net.buildertools.network.FabricNetwork.sendToPlayer(player, new RotationSyncPacket(
                    e.getKey(), e.getValue().state(), e.getValue().yaw(),
                    e.getValue().pitch(), e.getValue().billboard(), false, c.x, c.y, c.z));
        }
    }

    private static void broadcast(ServerLevel level, RotationSyncPacket packet) {
        for (ServerPlayer player : level.players()) {
            net.buildertools.network.FabricNetwork.sendToPlayer(player, packet);
        }
    }

    /** Client mirror: applied by {@link RotationSyncPacket}. */
    public static void applyClientSync(BlockPos pos, RotationData data, boolean remove) {
        System.out.println("[BuilderTools] client sync " + pos + " remove=" + remove
                + (data != null ? " yaw=" + data.yaw() : ""));
        if (remove) {
            CLIENT.remove(pos);
        } else {
            CLIENT.put(pos.immutable(), data);
        }
    }

    /**
     * All rotated blocks whose world-space bounding box may intersect the given box (a cheap cell
     * pre-filter, then the tight rotated bounding box). Used by the collision and raycast hooks.
     */
    public static List<Map.Entry<BlockPos, RotationData>> getInBox(BlockGetter level, AABB box) {
        // Do not pre-filter by the center alone. A rotated model (and especially a block with a
        // model offset or a custom model extending outside 0..1) can intersect the query while
        // its pivot is outside the old two-block center window. The rotated broadphase below is
        // deliberately conservative; the exact triangle/shape test still decides the hit.
        List<Map.Entry<BlockPos, RotationData>> result = new ArrayList<>();
        if (level instanceof ServerLevel serverLevel) {
            for (Map.Entry<BlockPos, RotationData> e : RotationSavedData.of(serverLevel).all().entrySet()) {
                if (rotatedBounds(e).intersects(box)) {
                    result.add(e);
                }
            }
        } else {
            for (Map.Entry<BlockPos, RotationData> e : CLIENT.entrySet()) {
                if (rotatedBounds(e).intersects(box)) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    /** A snapshot of the client mirror (for the per-frame renderer). */
    public static List<Map.Entry<BlockPos, RotationData>> clientEntries() {
        return new ArrayList<>(CLIENT.entrySet());
    }

    private static AABB rotatedBounds(Map.Entry<BlockPos, RotationData> e) {
        BlockPos pos = e.getKey();
        RotationData data = e.getValue();
        Vec3 c = data.center(pos);
        // This is only a broadphase test. Use a conservative model envelope, not the collision or
        // outline accessor: during Entity#collide smoothable states intentionally return an empty
        // per-cell shape, and custom blocks can render beyond the vanilla 0..1 cell. The envelope
        // also includes normal model offsets, so the exact triangle test cannot be skipped at a
        // protruding corner.
        AABB modelCell = new AABB(-1.0, -1.0, -1.0, 2.0, 2.0, 2.0);
        return net.buildertools.util.OffGridTransform.boxAround(
                c.x, c.y, c.z, data.yaw(), data.pitch(), modelCell);
    }

    public static void clearClient() {
        CLIENT.clear();
    }
}
