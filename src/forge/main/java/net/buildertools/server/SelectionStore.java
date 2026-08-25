package net.buildertools.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player selection, kept server-side so the {@code /builder} commands operate on the same
 * region the client renders. The client keeps this in sync via {@code SelectionSyncPacket}; the
 * two raw corners are stored so {@code pos1}/{@code pos2} can update a single corner.
 */
public final class SelectionStore {
    /** A normalised selection region (inclusive min/max). */
    public record Region(BlockPos min, BlockPos max) {
        public long volume() {
            return (long) (max.getX() - min.getX() + 1)
                    * (max.getY() - min.getY() + 1)
                    * (max.getZ() - min.getZ() + 1);
        }
    }

    private record CornerPair(BlockPos corner1, BlockPos corner2) {
    }

    private static final Map<UUID, CornerPair> SELECTIONS = new HashMap<>();

    private SelectionStore() {
    }

    public static void set(ServerPlayer player, BlockPos corner1, BlockPos corner2) {
        SELECTIONS.put(player.getUUID(), new CornerPair(corner1.immutable(), corner2.immutable()));
    }

    public static void setCorner1(ServerPlayer player, BlockPos pos) {
        CornerPair current = SELECTIONS.get(player.getUUID());
        if (current == null) {
            set(player, pos, pos);
        } else {
            set(player, pos, current.corner2);
        }
    }

    public static void setCorner2(ServerPlayer player, BlockPos pos) {
        CornerPair current = SELECTIONS.get(player.getUUID());
        if (current == null) {
            set(player, pos, pos);
        } else {
            set(player, current.corner1, pos);
        }
    }

    /** Sets the region as a normalised min/max pair (used by expand/contract/shift). */
    public static void setRegion(ServerPlayer player, BlockPos min, BlockPos max) {
        set(player, min, max);
    }

    public static Region get(ServerPlayer player) {
        CornerPair pair = SELECTIONS.get(player.getUUID());
        if (pair == null) {
            return null;
        }
        BlockPos min = new BlockPos(
                Math.min(pair.corner1.getX(), pair.corner2.getX()),
                Math.min(pair.corner1.getY(), pair.corner2.getY()),
                Math.min(pair.corner1.getZ(), pair.corner2.getZ()));
        BlockPos max = new BlockPos(
                Math.max(pair.corner1.getX(), pair.corner2.getX()),
                Math.max(pair.corner1.getY(), pair.corner2.getY()),
                Math.max(pair.corner1.getZ(), pair.corner2.getZ()));
        return new Region(min, max);
    }

    public static void clear(ServerPlayer player) {
        SELECTIONS.remove(player.getUUID());
    }

    public static void remove(ServerPlayer player) {
        SELECTIONS.remove(player.getUUID());
    }
}
