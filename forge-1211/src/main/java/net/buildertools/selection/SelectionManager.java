package net.buildertools.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/**
 * Client-side state for the builder tools.
 *
 * <p>Selections are per-client: the corners are used for rendering and are sent to the server
 * along with every operation, which is what makes the tools work in single player and on servers
 * without any per-player server state.
 */
public final class SelectionManager {
    private static BlockPos corner1;
    private static BlockPos corner2;
    private static Entity selectedEntity;
    private static boolean entityFrozen;
    private static boolean dirty;
    private static boolean suppressSync;

    private SelectionManager() {
    }

    public static void setCorner1(BlockPos pos) {
        corner1 = pos.immutable();
        markDirty();
    }

    public static void setCorner2(BlockPos pos) {
        corner2 = pos.immutable();
        markDirty();
    }

    public static BlockPos getCorner1() {
        return corner1;
    }

    public static BlockPos getCorner2() {
        return corner2;
    }

    public static boolean hasSelection() {
        return corner1 != null && corner2 != null;
    }

    public static BlockPos getMin() {
        if (!hasSelection()) {
            return null;
        }
        return new BlockPos(
                Math.min(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()),
                Math.min(corner1.getZ(), corner2.getZ()));
    }

    public static BlockPos getMax() {
        if (!hasSelection()) {
            return null;
        }
        return new BlockPos(
                Math.max(corner1.getX(), corner2.getX()),
                Math.max(corner1.getY(), corner2.getY()),
                Math.max(corner1.getZ(), corner2.getZ()));
    }

    public static void clearSelection() {
        corner1 = null;
        corner2 = null;
        markDirty();
    }

    /** Marks the selection as changed so the client flushes it to the server (for /builder). */
    private static void markDirty() {
        if (!suppressSync) {
            dirty = true;
        }
    }

    /** @return true if the selection changed since the last flush. */
    public static boolean consumeDirty() {
        boolean wasDirty = dirty;
        dirty = false;
        return wasDirty;
    }

    /** Applies a region pushed back from the server (expand/contract/shift) without re-syncing. */
    public static void applyServerSync(BlockPos min, BlockPos max) {
        suppressSync = true;
        corner1 = min.immutable();
        corner2 = max.immutable();
        suppressSync = false;
        dirty = false;
    }

    public static void applyServerClear() {
        suppressSync = true;
        corner1 = null;
        corner2 = null;
        suppressSync = false;
        dirty = false;
    }

    public static void setSelectedEntity(Entity entity) {
        selectedEntity = entity;
        entityFrozen = false;
    }

    public static Entity getSelectedEntity() {
        return selectedEntity;
    }

    public static boolean hasSelectedEntity() {
        return selectedEntity != null && !selectedEntity.isRemoved();
    }

    public static void clearSelectedEntity() {
        selectedEntity = null;
        entityFrozen = false;
    }

    /** Client-side mirror of whether the selected entity is currently frozen. */
    public static void setEntityFrozen(boolean frozen) {
        entityFrozen = frozen;
    }

    public static boolean isEntityFrozen() {
        return entityFrozen;
    }

    public static void clearAll() {
        corner1 = null;
        corner2 = null;
        selectedEntity = null;
    }
}
