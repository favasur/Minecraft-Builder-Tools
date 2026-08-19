package net.buildertools.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player clipboard, kept server-side so copy/paste works identically in single player and on
 * dedicated servers. Not persisted across server restarts.
 */
public final class ClipboardStore {
    private static final Map<UUID, CompoundTag> CLIPBOARDS = new HashMap<>();

    private ClipboardStore() {
    }

    public static CompoundTag get(ServerPlayer player) {
        return CLIPBOARDS.get(player.getUUID());
    }

    public static void set(ServerPlayer player, CompoundTag clipboard) {
        CLIPBOARDS.put(player.getUUID(), clipboard);
    }

    public static void remove(ServerPlayer player) {
        CLIPBOARDS.remove(player.getUUID());
    }
}
