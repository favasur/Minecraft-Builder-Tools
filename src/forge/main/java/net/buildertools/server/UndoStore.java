package net.buildertools.server;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player stacks of the last few block-changing operations. Not persisted.
 */
public final class UndoStore {
    private static final int MAX_OPS = 50;
    private static final Map<UUID, ArrayDeque<List<BlockChange>>> UNDO = new HashMap<>();
    private static final Map<UUID, ArrayDeque<List<BlockChange>>> REDO = new HashMap<>();

    private UndoStore() {
    }

    private static ArrayDeque<List<BlockChange>> undoStack(ServerPlayer player) {
        return UNDO.computeIfAbsent(player.getUUID(), uuid -> new ArrayDeque<>());
    }

    private static ArrayDeque<List<BlockChange>> redoStack(ServerPlayer player) {
        return REDO.computeIfAbsent(player.getUUID(), uuid -> new ArrayDeque<>());
    }

    /** Pushes a new operation; any redo history is discarded. */
    public static void push(ServerPlayer player, List<BlockChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        ArrayDeque<List<BlockChange>> stack = undoStack(player);
        stack.push(changes);
        while (stack.size() > MAX_OPS) {
            stack.removeLast();
        }
        REDO.remove(player.getUUID());
    }

    /** @return true if an operation was undone */
    public static boolean undo(ServerPlayer player) {
        ArrayDeque<List<BlockChange>> stack = UNDO.get(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            BuilderServerHandler.sendMessage(player, "Nothing to undo.");
            return false;
        }
        List<BlockChange> changes = stack.pop();
        BuilderServerHandler.applyChanges(player.level(), changes);
        redoStack(player).push(changes);
        BuilderServerHandler.sendMessage(player, "Undid " + changes.size() + " block change(s).");
        return true;
    }

    /** @return true if an operation was redone */
    public static boolean redo(ServerPlayer player) {
        ArrayDeque<List<BlockChange>> stack = REDO.get(player.getUUID());
        if (stack == null || stack.isEmpty()) {
            BuilderServerHandler.sendMessage(player, "Nothing to redo.");
            return false;
        }
        List<BlockChange> changes = stack.pop();
        BuilderServerHandler.applyChanges(player.level(), changes);
        undoStack(player).push(changes);
        BuilderServerHandler.sendMessage(player, "Redid " + changes.size() + " block change(s).");
        return true;
    }

    public static void remove(ServerPlayer player) {
        UNDO.remove(player.getUUID());
        REDO.remove(player.getUUID());
    }
}
