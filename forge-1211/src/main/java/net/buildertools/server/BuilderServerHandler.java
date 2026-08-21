package net.buildertools.server;

import net.buildertools.network.packet.SelectionSyncPacket;
import net.buildertools.registry.ModSounds;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EntityType;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.registry.ModEntities;
import net.buildertools.mixin.BlockDisplayAccessor;
import net.buildertools.mixin.DisplayAccessor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side implementation of every builder tool operation. All methods run on the server thread
 * and validate positions, distances and limits so the tools are safe to use in multiplayer.
 */
public final class BuilderServerHandler {
    /** Maximum number of blocks a single fill/paste/copy may touch. */
    static final int MAX_BLOCKS = 32768;
    /** Operations farther than this (in blocks) from the player are rejected. */
    static final double MAX_DISTANCE = 256.0;

    private record Region(BlockPos min, BlockPos max) {
    }

    private record RecentOffGrid(BlockPos pos, long tick) {
    }

    /** Cells that just received an off-grid block per player, so the server can cancel the
     *  vanilla placement that arrives alongside the mod's OffGridBlockPacket. */
    private static final Map<UUID, RecentOffGrid> RECENT_OFF_GRID = new HashMap<>();

    private BuilderServerHandler() {
    }

    public static void sendMessage(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[Builder] " + message));
    }

    /** Sends a message and plays the error sound. */
    static void sendError(ServerPlayer player, String message) {
        sendMessage(player, message);
        playSound(player, ModSounds.ERROR.get());
    }

    /** Plays a sound only for the acting player (ported sound set). */
    private static void playSound(ServerPlayer player, SoundEvent sound) {
        player.playNotifySound(sound, SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------
    // Selection operations
    // ------------------------------------------------------------------

    public static void fillSelection(ServerPlayer player, BlockPos corner1, BlockPos corner2) {
        Region region = validateSelection(player, corner1, corner2);
        if (region == null) {
            return;
        }

        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            sendError(player, "Hold a block in your main hand to fill with.");
            return;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        CompoundTag blockEntityTag = blockEntityTag(held);

        Level level = player.level();
        List<BlockChange> changes = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(region.min(), region.max())) {
            changes.add(capture(level, pos.immutable()));
            setBlockWithEntity(level, pos, state, blockEntityTag);
        }

        UndoStore.push(player, changes);
        sendMessage(player, "Filled " + changes.size() + " block(s).");
        playSound(player, ModSounds.FILL.get());
    }

    /**
     * Alt+drag stretch: remaps the selection's blocks proportionally along the dragged axis
     * (rubber-sheet style), filling the new region. The face opposite the dragged one stays put;
     * content is scaled between it and the dragged face, so stretching duplicates blocks and
     * compressing trims them (the trimmed part becomes air).
     */
    public static void stretchSelection(ServerPlayer player, int axis, boolean positive,
                                        BlockPos origMin, BlockPos origMax, BlockPos newMin, BlockPos newMax) {
        if (axis < 0 || axis > 2 || origMin == null || origMax == null || newMin == null || newMax == null) {
            sendError(player, "Invalid stretch parameters.");
            return;
        }
        BlockPos oMin = new BlockPos(Math.min(origMin.getX(), origMax.getX()), Math.min(origMin.getY(), origMax.getY()), Math.min(origMin.getZ(), origMax.getZ()));
        BlockPos oMax = new BlockPos(Math.max(origMin.getX(), origMax.getX()), Math.max(origMin.getY(), origMax.getY()), Math.max(origMin.getZ(), origMax.getZ()));
        BlockPos nMin = new BlockPos(Math.min(newMin.getX(), newMax.getX()), Math.min(newMin.getY(), newMax.getY()), Math.min(newMin.getZ(), newMax.getZ()));
        BlockPos nMax = new BlockPos(Math.max(newMin.getX(), newMax.getX()), Math.max(newMin.getY(), newMax.getY()), Math.max(newMin.getZ(), newMax.getZ()));

        // The stretch works on the union of the original and new regions.
        BlockPos uMin = new BlockPos(
                Math.min(oMin.getX(), nMin.getX()), Math.min(oMin.getY(), nMin.getY()), Math.min(oMin.getZ(), nMin.getZ()));
        BlockPos uMax = new BlockPos(
                Math.max(oMax.getX(), nMax.getX()), Math.max(oMax.getY(), nMax.getY()), Math.max(oMax.getZ(), nMax.getZ()));

        long volume = (long) (uMax.getX() - uMin.getX() + 1)
                * (uMax.getY() - uMin.getY() + 1)
                * (uMax.getZ() - uMin.getZ() + 1);
        if (volume > MAX_BLOCKS) {
            sendError(player, "Selection is too large (max " + MAX_BLOCKS + " blocks).");
            return;
        }
        Level level = player.level();
        if (!level.hasChunksAt(uMin, uMax)) {
            sendError(player, "Selection is not fully loaded.");
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(uMin)) > MAX_DISTANCE * MAX_DISTANCE
                || player.distanceToSqr(Vec3.atCenterOf(uMax)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Selection is too far away (max " + (int) MAX_DISTANCE + " blocks).");
            return;
        }

        // The opposite face of the dragged one stays fixed; content is scaled from it toward the
        // dragged face's new position.
        int fixed = positive ? coord(oMin, axis) : coord(oMax, axis);
        int oDrag = positive ? coord(oMax, axis) : coord(oMin, axis);
        int nDrag = positive ? coord(nMax, axis) : coord(nMin, axis);
        int oExtent = Math.abs(oDrag - fixed);
        int nExtent = Math.abs(nDrag - fixed);

        List<BlockChange> changes = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(uMin, uMax)) {
            BlockPos p = pos.immutable();
            int pc = coord(p, axis);
            int srcC;
            if (nExtent == 0) {
                srcC = fixed;
            } else {
                double t = positive ? (double) (pc - fixed) / nExtent : (double) (fixed - pc) / nExtent;
                srcC = fixed + (positive ? 1 : -1) * (int) Math.round(t * oExtent);
            }

            BlockState state;
            CompoundTag nbt = null;
            if (srcC < coord(oMin, axis) || srcC > coord(oMax, axis)) {
                // Compressed away: outside the original span, the content is cut off.
                state = Blocks.AIR.defaultBlockState();
            } else {
                BlockPos src = setCoord(p, axis, srcC);
                state = level.getBlockState(src);
                BlockEntity blockEntity = level.getBlockEntity(src);
                if (blockEntity != null) {
                    nbt = blockEntity.saveWithFullMetadata(level.registryAccess());
                }
            }
            changes.add(capture(level, p));
            setBlockWithEntity(level, p, state, nbt);
        }

        UndoStore.push(player, changes);
        // Keep the region (and the client's selection box) at the new size.
        SelectionStore.setRegion(player, nMin, nMax);
        net.buildertools.network.ModPackets.sendToPlayer(new SelectionSyncPacket(
                true, nMin.getX(), nMin.getY(), nMin.getZ(), nMax.getX(), nMax.getY(), nMax.getZ()), player);
        sendMessage(player, "Stretched " + changes.size() + " block(s).");
        playSound(player, ModSounds.FILL.get());
    }

    /** Returns the given axis coordinate of a position (0=x, 1=y, 2=z). */
    private static int coord(BlockPos pos, int axis) {
        return switch (axis) {
            case 0 -> pos.getX();
            case 1 -> pos.getY();
            default -> pos.getZ();
        };
    }

    /** Returns a copy of {@code pos} with the given axis coordinate replaced. */
    private static BlockPos setCoord(BlockPos pos, int axis, int value) {
        return switch (axis) {
            case 0 -> new BlockPos(value, pos.getY(), pos.getZ());
            case 1 -> new BlockPos(pos.getX(), value, pos.getZ());
            default -> new BlockPos(pos.getX(), pos.getY(), value);
        };
    }

    // ------------------------------------------------------------------
    // Brush tools (paint / scatter / smooth)
    // ------------------------------------------------------------------

    /** Brush radius in blocks. */
    private static final int BRUSH_RADIUS = 4;

    public static void paint(ServerPlayer player, BlockPos center) {
        if (!validateBrushTarget(player, center)) {
            return;
        }
        BlockState state = blockStateFromHand(player);
        if (state == null) {
            return;
        }
        CompoundTag blockEntityTag = blockEntityTag(player.getMainHandItem());
        Level level = player.level();

        List<BlockChange> changes = new ArrayList<>();
        for (BlockPos pos : spherePositions(center)) {
            changes.add(capture(level, pos));
            setBlockWithEntity(level, pos, state, blockEntityTag);
            if (changes.size() >= MAX_BLOCKS) {
                break;
            }
        }
        UndoStore.push(player, changes);
        sendMessage(player, "Painted " + changes.size() + " block(s).");
        playSound(player, ModSounds.PAINT.get());
    }

    public static void scatter(ServerPlayer player, BlockPos center) {
        if (!validateBrushTarget(player, center)) {
            return;
        }
        BlockState state = blockStateFromHand(player);
        if (state == null) {
            return;
        }
        Level level = player.level();

        List<BlockChange> changes = new ArrayList<>();
        for (BlockPos pos : spherePositions(center)) {
            if (level.random.nextDouble() > 0.5) {
                continue;
            }
            // Only scatter into air that sits on a solid block, so it paints onto surfaces.
            if (!level.getBlockState(pos).isAir()) {
                continue;
            }
            if (!level.getBlockState(pos.below()).isSolid()) {
                continue;
            }
            changes.add(capture(level, pos));
            level.setBlock(pos, state, 3);
        }
        UndoStore.push(player, changes);
        sendMessage(player, "Scattered " + changes.size() + " block(s).");
        playSound(player, ModSounds.SCATTER.get());
    }

    public static void smooth(ServerPlayer player, BlockPos center) {
        if (!validateBrushTarget(player, center)) {
            return;
        }
        Level level = player.level();

        // Collect one surface column per (x,z) in the disc around the clicked block.
        List<Column> columns = new ArrayList<>();
        for (int dx = -BRUSH_RADIUS; dx <= BRUSH_RADIUS; dx++) {
            for (int dz = -BRUSH_RADIUS; dz <= BRUSH_RADIUS; dz++) {
                if (dx * dx + dz * dz > BRUSH_RADIUS * BRUSH_RADIUS) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int y = center.getY() + 3;
                while (y > center.getY() - 6 && level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                    y--;
                }
                BlockState top = level.getBlockState(new BlockPos(x, y, z));
                if (top.isAir()) {
                    continue;
                }
                columns.add(new Column(x, y, z, top));
            }
        }
        if (columns.isEmpty()) {
            sendMessage(player, "Nothing to smooth.");
            return;
        }

        double total = 0;
        for (Column column : columns) {
            total += column.y;
        }
        int targetY = (int) Math.round(total / columns.size());

        List<BlockChange> changes = new ArrayList<>();
        for (Column column : columns) {
            if (column.y == targetY) {
                continue;
            }
            if (column.y > targetY) {
                // Column is too high: shave it down to the target height.
                for (int y = targetY + 1; y <= column.y; y++) {
                    BlockPos pos = new BlockPos(column.x, y, column.z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    changes.add(capture(level, pos));
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                }
            } else {
                // Column is too low: raise it using its own surface block.
                for (int y = column.y + 1; y <= targetY; y++) {
                    BlockPos pos = new BlockPos(column.x, y, column.z);
                    changes.add(capture(level, pos));
                    level.setBlock(pos, column.state, 3);
                }
            }
        }
        UndoStore.push(player, changes);
        sendMessage(player, "Smoothed " + changes.size() + " block(s).");
        playSound(player, ModSounds.SMOOTH.get());
    }

    private record Column(int x, int y, int z, BlockState state) {
    }

    public static void copySelection(ServerPlayer player, BlockPos corner1, BlockPos corner2) {
        Region region = validateSelection(player, corner1, corner2);
        if (region == null) {
            return;
        }

        Level level = player.level();
        ListTag entries = new ListTag();
        BlockPos min = region.min();
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, region.max())) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", pos.getX() - min.getX());
            entry.putInt("y", pos.getY() - min.getY());
            entry.putInt("z", pos.getZ() - min.getZ());
            entry.putString("state", BlockStateParser.serialize(state));
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                entry.put("nbt", blockEntity.saveWithFullMetadata(level.registryAccess()));
            }
            entries.add(entry);
            count++;
        }

        CompoundTag clipboard = new CompoundTag();
        clipboard.put("entries", entries);
        ClipboardStore.set(player, clipboard);
        sendMessage(player, "Copied " + count + " block(s) to clipboard.");
        playSound(player, ModSounds.COPY.get());
    }

    public static void paste(ServerPlayer player, BlockPos anchor) {
        if (anchor == null) {
            sendError(player, "Invalid paste position.");
            return;
        }
        Level level = player.level();
        if (player.distanceToSqr(Vec3.atCenterOf(anchor)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Paste position is too far away.");
            return;
        }

        CompoundTag clipboard = ClipboardStore.get(player);
        if (clipboard == null || clipboard.isEmpty()) {
            sendError(player, "Clipboard is empty - copy a selection first.");
            return;
        }

        ListTag entries = clipboard.getList("entries", Tag.TAG_COMPOUND);
        List<BlockChange> changes = new ArrayList<>();
        int count = 0;
        int skipped = 0;
        for (Tag tag : entries) {
            CompoundTag entry = (CompoundTag) tag;
            BlockPos pos = anchor.offset(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            if (!level.hasChunkAt(pos)) {
                skipped++;
                continue;
            }
            BlockState state;
            try {
                state = BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK),
                        entry.getString("state"), false).blockState();
            } catch (Exception ex) {
                skipped++;
                continue;
            }
            changes.add(capture(level, pos.immutable()));
            level.setBlock(pos, state, 3);
            if (entry.contains("nbt", Tag.TAG_COMPOUND)) {
                BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, entry.getCompound("nbt"), level.registryAccess());
                if (blockEntity != null) {
                    level.setBlockEntity(blockEntity);
                }
            }
            count++;
        }

        UndoStore.push(player, changes);
        if (count == 0) {
            sendError(player, "Nothing was pasted (clipboard empty or area not loaded).");
        } else {
            String skippedNote = skipped > 0 ? " (" + skipped + " skipped - not loaded)" : "";
            sendMessage(player, "Pasted " + count + " block(s)." + skippedNote);
            playSound(player, ModSounds.PASTE.get());
        }
    }

    public static void undo(ServerPlayer player) {
        if (UndoStore.undo(player)) {
            playSound(player, ModSounds.UNDO.get());
        } else {
            playSound(player, ModSounds.ERROR.get());
        }
    }

    // ------------------------------------------------------------------
    // Off-grid block placement (rotated block displays)
    // ------------------------------------------------------------------

    /** Tag put on every off-grid display so we can find and manage them. */
    public static final String OFF_GRID_TAG = "buildertools.offgrid";

    /**
     * Spawns a rotated block display at the cell instead of a grid block, so the block can sit at
     * any angle (Hytale-style offset placement). The yaw is stored both in the display
     * transformation (what renders) and in the entity's yaw (so the client can read it back for
     * inheritance without needing the private transformation getter).
     */
    /**
     * Places a NEW rotated block into the mod's block layer: the held vanilla block goes into the
     * layer (the block itself stays the block it is - same shading, breaking, drops), the vanilla
     * cell stays AIR, and the layer entry carries the state + rotation. Re-rotating an already
     * placed block updates its entry in place.
     */
    public static void handleBlockRotation(ServerPlayer player, BlockPos cell, float yaw, float pitch,
                                           boolean billboard) {
        if (player.distanceToSqr(Vec3.atCenterOf(cell)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Position is too far away.");
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.hasChunkAt(cell)) {
            sendError(player, "Area is not loaded.");
            return;
        }
        RotationData existing = RotationStore.get(level, cell);
        if (existing != null) {
            // Re-rotate the block already in the layer, strictly in place (its state stays).
            RotationStore.set(level, cell, new RotationData(existing.state(), yaw, pitch, billboard));
            recordOffGridPlacement(player, cell);
            sendMessage(player, "Rotated block (yaw " + Math.round(yaw) + ", pitch " + Math.round(pitch)
                    + (billboard ? ", billboard" : "") + ").");
            playSound(player, ModSounds.SET_CORNER_1.get());
            return;
        }
        // New placement: the held block into the cell, then record its rotation.
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            sendError(player, "Hold a block in your main hand to place.");
            return;
        }
        if (player.getBoundingBox().intersects(new AABB(cell))) {
            sendError(player, "You're in the way - move back first.");
            return;
        }
        // The vanilla block sitting in the cell is replaced (dropped in survival), then the cell
        // becomes air - the rotated block lives in the mod's layer from now on.
        if (!level.getBlockState(cell).isAir()) {
            level.destroyBlock(cell, !player.getAbilities().instabuild);
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        RotationStore.set(level, cell, new RotationData(state, yaw, pitch, billboard));
        // Remember the cell: the vanilla use-item packet for the same click may arrive next,
        // and the server-side right-click handler uses this record to cancel the duplicate.
        recordOffGridPlacement(player, cell);
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        sendMessage(player, "Placed block (yaw " + Math.round(yaw) + ", pitch " + Math.round(pitch)
                + (billboard ? ", billboard" : "") + ").");
        playSound(player, ModSounds.SET_CORNER_1.get());
    }

    /**
     * Breaks the rotated block in the given cell of the mod's layer like a normal block: drops the
     * block's item in survival and removes the entry. The vanilla cell is already air.
     */
    public static void handleFreeBlockBreak(ServerPlayer player, BlockPos cell) {
        ServerLevel level = player.serverLevel();
        RotationData data = RotationStore.get(level, cell);
        if (data == null) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(cell)) > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        if (!player.getAbilities().instabuild) {
            BlockState state = data.state();
            if (state != null && !state.isAir()) {
                level.addFreshEntity(new ItemEntity(level,
                        cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5,
                        new ItemStack(state.getBlock())));
            }
        }
        RotationStore.remove(level, cell);
        playSound(player, ModSounds.SET_CORNER_2.get());
    }

    /**
     * Legacy off-grid entity placement (old worlds): re-rotating a legacy entity at the exact
     * spot keeps the entity pair; anything else becomes a NEW rotated vanilla block (the modern
     * path). Kept so already-placed legacy entities keep working.
     */
    public static void placeOffGrid(ServerPlayer player, double cx, double cy, double cz, float yaw, float pitch,
                                    boolean billboard) {
        Level level = player.level();
        OffGridBlockEntity atSpot = findOffGrid(level, cx, cy, cz);
        if (atSpot != null && atSpot.modelCenter().distanceToSqr(new Vec3(cx, cy, cz)) < 0.0025) {
            // Legacy entity re-rotation: replace the pair with a fresh one at the same rotation.
            BlockState state = atSpot.getRepresentedState();
            Vec3 c = atSpot.modelCenter();
            atSpot.discardWithDisplay();
            spawnLegacyPair(level, c.x, c.y, c.z, state, yaw, pitch, billboard);
            recordOffGridPlacement(player, BlockPos.containing(cx, cy, cz));
            sendMessage(player, "Rotated legacy block (yaw " + Math.round(yaw) + ", pitch " + Math.round(pitch) + ").");
            playSound(player, ModSounds.SET_CORNER_1.get());
            return;
        }
        handleBlockRotation(player, BlockPos.containing(cx, cy, cz), yaw, pitch, billboard);
    }

    /** Spawns a legacy off-grid entity pair (display + collidable entity) for old worlds. */
    private static void spawnLegacyPair(Level level, double cx, double cy, double cz, BlockState state,
                                        float yaw, float pitch, boolean billboard) {
        Display.BlockDisplay display = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        ((BlockDisplayAccessor) (Object) display).buildertools$setBlockState(state);
        display.setPos(cx - 0.5, cy - 0.5, cz - 0.5);
        ((DisplayAccessor) (Object) display).buildertools$setTransformation(OffGridTransform.transformation(yaw, pitch));
        ((DisplayAccessor) (Object) display).buildertools$setBillboardConstraints(billboard
                ? Display.BillboardConstraints.CENTER
                : Display.BillboardConstraints.FIXED);
        display.addTag(OFF_GRID_TAG);
        level.addFreshEntity(display);

        OffGridBlockEntity block = ModEntities.OFF_GRID_BLOCK.get().create(level);
        if (block != null) {
            block.setRepresentedState(state);
            block.setPlacementRotation(yaw, pitch);
            block.setModelCenter(cx, cy, cz);
            block.setBillboard(billboard);
            block.setDisplayUuid(display.getUUID());
            block.addTag(OFF_GRID_TAG);
            level.addFreshEntity(block);
        }
    }

    /** Remembers the cell so the server's own right-click handling can cancel the vanilla block. */
    public static void recordOffGridPlacement(Player player, BlockPos pos) {
        RECENT_OFF_GRID.put(player.getUUID(), new RecentOffGrid(pos.immutable(), player.level().getGameTime()));
    }

    /** True when an off-grid block was just placed in this cell (within ~3 seconds). */
    public static boolean isRecentOffGridPlacement(Player player, BlockPos pos) {
        RecentOffGrid entry = RECENT_OFF_GRID.get(player.getUUID());
        return entry != null
                && entry.pos().equals(pos)
                && player.level().getGameTime() - entry.tick() < 60L;
    }

    /** Removes the recent-placement record (called on logout). */
    public static void removeRecentOffGrid(UUID uuid) {
        RECENT_OFF_GRID.remove(uuid);
    }

    /**
     * True when a vanilla block placed into {@code cell} (a full cube there) would penetrate any
     * off-grid block's actual rotated model. Off-grid blocks are real geometry, so vanilla blocks
     * cannot be placed on top of, or clipping through, them - flush-adjacent placement (touching)
     * is still allowed.
     */
    public static boolean vanillaPlacementOverlapsOffGrid(Level level, BlockPos cell) {
        AABB cubeShape = new AABB(0, 0, 0, 1, 1, 1);
        for (OffGridBlockEntity other : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(cell).inflate(1.5))) {
            if (!other.getTags().contains(OFF_GRID_TAG)) {
                continue;
            }
            if (OffGridTransform.modelsOverlap(
                    cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, 0.0f, 0.0f, cubeShape,
                    other.modelCenter().x, other.modelCenter().y, other.modelCenter().z,
                    other.getPlacementYaw(), other.getPlacementPitch(),
                    other.getRepresentedState().getCollisionShape(level, BlockPos.ZERO).bounds())) {
                return true;
            }
        }
        return false;
    }

    /** Removes the off-grid display in the cell (dropping its item in survival) and plays a break sound. */
    public static void removeOffGrid(ServerPlayer player, double cx, double cy, double cz) {
        if (player.distanceToSqr(cx, cy, cz) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Position is too far away.");
            return;
        }
        Level level = player.level();
        OffGridBlockEntity block = findOffGrid(level, cx, cy, cz);
        if (block == null) {
            return;
        }
        if (!player.getAbilities().instabuild) {
            BlockState state = block.getRepresentedState();
            if (!state.isAir()) {
                level.addFreshEntity(new ItemEntity(level,
                        cx, cy + 0.25, cz,
                        new ItemStack(state.getBlock())));
            }
        }
        block.discardWithDisplay();
        playSound(player, ModSounds.SET_CORNER_2.get());
    }

    /** Finds the solid off-grid block whose model center is nearest the given point, or null. */
    public static OffGridBlockEntity findOffGrid(Level level, double x, double y, double z) {
        double best = 0.36; // within 0.6 blocks
        OffGridBlockEntity bestBlock = null;
        for (OffGridBlockEntity block : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(x - 0.75, y - 0.75, z - 0.75, x + 0.75, y + 0.75, z + 0.75))) {
            if (!block.getTags().contains(OFF_GRID_TAG)) {
                continue;
            }
            double d = block.modelCenter().distanceToSqr(new Vec3(x, y, z));
            if (d < best) {
                best = d;
                bestBlock = block;
            }
        }
        return bestBlock;
    }

    /** Finds the solid off-grid block occupying the given cell, or null. */
    public static OffGridBlockEntity findOffGrid(Level level, BlockPos pos) {
        for (OffGridBlockEntity block : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                        pos.getX() + 2, pos.getY() + 2, pos.getZ() + 2))) {
            if (block.getTags().contains(OFF_GRID_TAG) && block.cell().equals(pos)) {
                return block;
            }
        }
        return null;
    }


    // ------------------------------------------------------------------
    // Entity operations
    // ------------------------------------------------------------------

    /**
     * Moves (and optionally re-rotates) an entity. This is called continuously while dragging or
     * rotating, so it must not send chat messages or play sounds - the client plays the feedback
     * for discrete actions itself. Off-grid blocks get special handling so their linked display
     * (the thing that actually renders the rotated model) follows both position and rotation,
     * keeping the visual in sync with the hitbox.
     */
    public static void moveEntity(ServerPlayer player, int entityId,
                                  double x, double y, double z, float yaw, float pitch, boolean headOnly) {
        Entity entity = validateEntity(player, entityId);
        if (entity == null) {
            return;
        }
        if (entity instanceof OffGridBlockEntity offGrid) {
            // Keep the block exactly where the client placed it (fractional model center, off the
            // grid - the whole point of off-grid blocks) and rotate it in place; the display
            // child moves to the matching position and takes the new rotation so visuals match
            // the hitbox (both derive from the same model center).
            double cx = x;
            double cy = y;
            double cz = z;
            offGrid.setModelCenter(cx, cy, cz);
            offGrid.setPlacementRotation(yaw, pitch);
            offGrid.setYRot(yaw);
            offGrid.setXRot(pitch);
            offGrid.setYHeadRot(yaw);
            offGrid.getDisplayUuid().ifPresent(uuid -> {
                Entity display = player.serverLevel().getEntity(uuid);
                if (display instanceof Display.BlockDisplay blockDisplay) {
                    blockDisplay.setPos(cx - 0.5, cy - 0.5, cz - 0.5);
                    ((DisplayAccessor) (Object) blockDisplay)
                            .buildertools$setTransformation(OffGridTransform.transformation(yaw, pitch));
                }
            });
            offGrid.setDeltaMovement(Vec3.ZERO);
            return;
        }
        entity.teleportTo(x, y, z);
        if (headOnly) {
            // Hytale Alt+R "rotate head": only the head yaw changes, the body stays put.
            entity.setYHeadRot(yaw);
        } else {
            entity.setYRot(yaw);
            entity.setXRot(pitch);
            entity.setYHeadRot(yaw);
        }
        entity.setDeltaMovement(Vec3.ZERO);
    }

    /** Spawns an entity of the given type at the position (used by the Entity Tool's E interface). */
    public static void spawnEntity(ServerPlayer player, ResourceLocation typeId, double x, double y, double z) {
        if (player.distanceToSqr(x, y, z) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Position is too far away.");
            return;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(typeId);
        if (type == null || type == EntityType.PLAYER) {
            sendError(player, "Unknown entity type.");
            return;
        }
        Level level = player.level();
        Entity entity = type.create(level);
        if (entity == null) {
            sendError(player, "Could not spawn entity.");
            return;
        }
        entity.moveTo(x, y, z, player.getYRot(), 0.0f);
        if (entity instanceof Mob mob) {
            mob.finalizeSpawn(player.serverLevel(),
                    level.getCurrentDifficultyAt(BlockPos.containing(x, y, z)),
                    MobSpawnType.COMMAND, null);
        }
        level.addFreshEntity(entity);
        sendMessage(player, "Spawned " + EntityType.getKey(type).getPath() + ".");
        playSound(player, ModSounds.ENTITY_DUPLICATE.get());
    }

    public static void deleteEntity(ServerPlayer player, int entityId) {
        Entity entity = validateEntity(player, entityId);
        if (entity == null) {
            return;
        }
        entity.discard();
        sendMessage(player, "Removed entity.");
        playSound(player, ModSounds.ENTITY_DELETE.get());
    }

    public static void duplicateEntity(ServerPlayer player, int entityId) {
        Entity entity = validateEntity(player, entityId);
        if (entity == null) {
            return;
        }
        Level level = player.level();

        CompoundTag tag = entity.saveWithoutId(new CompoundTag());
        tag.remove("UUID");
        tag.remove("UUIDMost");
        tag.remove("UUIDLeast");

        String id = tag.getString("id");
        EntityType<?> type = EntityType.byString(id).orElse(null);
        if (type == null) {
            sendMessage(player, "Could not duplicate entity (unknown type).");
            return;
        }
        Entity copy = type.create(level);
        if (copy == null) {
            sendMessage(player, "Could not duplicate entity.");
            return;
        }
        copy.load(tag);
        copy.moveTo(entity.getX() + 0.5, entity.getY(), entity.getZ() + 0.5, entity.getYRot(), entity.getXRot());
        level.addFreshEntity(copy);
        sendMessage(player, "Duplicated entity.");
        playSound(player, ModSounds.ENTITY_DUPLICATE.get());
    }

    /** All positions of a sphere of radius {@link #BRUSH_RADIUS} around the center (inclusive). */
    private static List<BlockPos> spherePositions(BlockPos center) {
        List<BlockPos> positions = new ArrayList<>();
        int r = BRUSH_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r))) {
            double dx = pos.getX() - center.getX() + 0.5;
            double dy = pos.getY() - center.getY() + 0.5;
            double dz = pos.getZ() - center.getZ() + 0.5;
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                positions.add(pos.immutable());
            }
        }
        return positions;
    }

    /** Validates that a brush position is in range and loaded. Returns false (with message) on failure. */
    private static boolean validateBrushTarget(ServerPlayer player, BlockPos pos) {
        if (pos == null) {
            sendError(player, "Invalid position.");
            return false;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Position is too far away.");
            return false;
        }
        if (!player.level().hasChunkAt(pos)) {
            sendError(player, "Area is not loaded.");
            return false;
        }
        return true;
    }

    /** Returns the default state of the block in the player's main hand, or null (with message) if not a block. */
    private static BlockState blockStateFromHand(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            sendError(player, "Hold a block in your main hand.");
            return null;
        }
        return blockItem.getBlock().defaultBlockState();
    }

    /** Extracts block entity data carried by an item (e.g. a chest with contents). */
    private static CompoundTag blockEntityTag(ItemStack held) {
        CustomData data = held.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
        return data != null ? data.copyTag() : null;
    }

    /** Sets a block and restores its block entity from the given NBT, if any. */
    static void setBlockWithEntity(Level level, BlockPos pos, BlockState state, CompoundTag blockEntityTag) {
        level.setBlock(pos, state, 3);
        if (blockEntityTag != null) {
            BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, blockEntityTag, level.registryAccess());
            if (blockEntity != null) {
                level.setBlockEntity(blockEntity);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Validates a selection region: size, loaded chunks and distance. Returns null and messages on failure. */
    private static Region validateSelection(ServerPlayer player, BlockPos corner1, BlockPos corner2) {
        if (corner1 == null || corner2 == null) {
            sendError(player, "Selection is empty - set both corners first.");
            return null;
        }
        BlockPos min = new BlockPos(
                Math.min(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()),
                Math.min(corner1.getZ(), corner2.getZ()));
        BlockPos max = new BlockPos(
                Math.max(corner1.getX(), corner2.getX()),
                Math.max(corner1.getY(), corner2.getY()),
                Math.max(corner1.getZ(), corner2.getZ()));

        long volume = (long) (max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1)
                * (max.getZ() - min.getZ() + 1);
        if (volume > MAX_BLOCKS) {
            sendError(player, "Selection is too large (max " + MAX_BLOCKS + " blocks).");
            return null;
        }
        Level level = player.level();
        if (!level.hasChunksAt(min, max)) {
            sendError(player, "Selection is not fully loaded.");
            return null;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(min)) > MAX_DISTANCE * MAX_DISTANCE
                || player.distanceToSqr(Vec3.atCenterOf(max)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Selection is too far away (max " + (int) MAX_DISTANCE + " blocks).");
            return null;
        }
        return new Region(min, max);
    }

    // ------------------------------------------------------------------
    // Creative settings (world + player)
    // ------------------------------------------------------------------

    /** Frozen day times per dimension while "Pause Time" is enabled. */
    private static final Map<ResourceKey<Level>, Long> PAUSED_DAY_TIME = new HashMap<>();

    /** Players who have No Clip enabled. {@code Player.tick()} resets {@code noPhysics} every
     *  tick (it is derived from spectator mode), so each tick we re-apply it for these players. */
    private static final Set<UUID> NO_CLIP_PLAYERS = new HashSet<>();

    public static void applyWorldSettings(ServerPlayer player, long timeOfDay, Boolean pauseTime, int weather) {
        ServerLevel level = player.serverLevel();
        if (timeOfDay >= 0) {
            level.setDayTime(timeOfDay);
            // Keep the frozen time in sync when the slider moves while paused.
            PAUSED_DAY_TIME.put(level.dimension(), timeOfDay);
        }
        if (pauseTime != null) {
            if (pauseTime) {
                PAUSED_DAY_TIME.putIfAbsent(level.dimension(), level.getDayTime());
            } else {
                PAUSED_DAY_TIME.remove(level.dimension());
            }
        }
        if (weather != net.buildertools.network.packet.WorldSettingsPacket.SKIP_WEATHER) {
            switch (weather) {
                case 0 -> level.setWeatherParameters(6000, 0, false, false);
                case 1 -> level.setWeatherParameters(0, 6000, true, false);
                case 2 -> level.setWeatherParameters(0, 6000, true, true);
                default -> {
                }
            }
        }
        sendMessage(player, "Updated world settings.");
    }

    /** Holds the day/night cycle still for every paused dimension (called every server tick). */
    public static void tickPausedLevels(MinecraftServer server) {
        if (PAUSED_DAY_TIME.isEmpty()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Long frozen = PAUSED_DAY_TIME.get(level.dimension());
            if (frozen != null) {
                level.setDayTime(frozen);
            }
        }
    }

    public static void applyPlayerAbilities(ServerPlayer player, float flySpeed, Boolean noClip, Boolean fly) {
        Abilities abilities = player.getAbilities();
        if (flySpeed >= 0.0f) {
            abilities.setFlyingSpeed(flySpeed);
        }
        if (noClip != null) {
            if (noClip) {
                NO_CLIP_PLAYERS.add(player.getUUID());
                player.noPhysics = true;
                abilities.mayfly = true;
                abilities.flying = true;
            } else {
                NO_CLIP_PLAYERS.remove(player.getUUID());
                player.noPhysics = false;
            }
        }
        if (fly != null) {
            abilities.mayfly = fly;
            abilities.flying = fly;
        }
        player.onUpdateAbilities();
        sendMessage(player, "Updated player settings.");
    }

    /** Whether the given player currently has No Clip enabled (kept true every tick). */
    public static boolean hasNoClip(Player player) {
        return NO_CLIP_PLAYERS.contains(player.getUUID());
    }

    public static void removeNoClip(Player player) {
        NO_CLIP_PLAYERS.remove(player.getUUID());
    }

    public static void freezeEntity(ServerPlayer player, int entityId, boolean freeze) {
        Entity entity = validateEntity(player, entityId);
        if (entity == null) {
            return;
        }
        if (entity instanceof Mob mob) {
            mob.setNoAi(freeze);
        } else {
            entity.setNoGravity(freeze);
        }
        entity.setDeltaMovement(Vec3.ZERO);
        sendMessage(player, freeze ? "Froze entity." : "Unfroze entity.");
        playSound(player, ModSounds.ENTITY_MOVE.get());
    }

    /** Validates that an entity exists, is not a player and is close enough. Returns null on failure. */
    private static Entity validateEntity(ServerPlayer player, int entityId) {
        Entity entity = player.level().getEntity(entityId);
        if (entity == null || entity.isRemoved()) {
            sendError(player, "Entity no longer exists.");
            return null;
        }
        if (entity instanceof Player) {
            sendError(player, "Cannot modify players.");
            return null;
        }
        if (player.distanceToSqr(entity.position()) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Entity is too far away.");
            return null;
        }
        return entity;
    }

    /** Captures the current state of a block (including block entity NBT) for the undo system. */
    static BlockChange capture(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        CompoundTag blockEntityNbt = null;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntityNbt = blockEntity.saveWithFullMetadata(level.registryAccess());
        }
        return new BlockChange(pos.immutable(), state, blockEntityNbt);
    }

    /** Restores previously captured block states (undo). */
    public static void applyChanges(Level level, List<BlockChange> changes) {
        for (BlockChange change : changes) {
            level.setBlock(change.pos(), change.state(), 3);
            if (change.blockEntityNbt() != null) {
                BlockEntity blockEntity = BlockEntity.loadStatic(change.pos(), change.state(), change.blockEntityNbt(), level.registryAccess());
                if (blockEntity != null) {
                    level.setBlockEntity(blockEntity);
                }
            }
        }
    }
}
