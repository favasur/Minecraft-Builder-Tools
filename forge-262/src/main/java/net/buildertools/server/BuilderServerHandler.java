package net.buildertools.server;

import net.buildertools.util.ArchBlockData;
import net.buildertools.util.ArchGeometry;
import net.buildertools.util.BezierBlockData;
import net.buildertools.util.BezierGeometry;
import net.buildertools.util.EllipseBlockData;
import net.buildertools.util.EllipseGeometry;
import net.buildertools.util.FaceFrame;
import net.buildertools.util.OffGridTransform;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess;
import net.buildertools.util.RotationData;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.mixin.BlockDisplayAccessor;
import net.buildertools.registry.ModEntities;
import net.buildertools.mixin.DisplayAccessor;
import net.buildertools.network.packet.SelectionSyncPacket;
import net.buildertools.registry.ModSounds;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    private static final Logger LOGGER = LogManager.getLogger("BuilderTools");

    private BuilderServerHandler() {
    }

    public static void sendMessage(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[Builder] " + message));
    }

    /** Logs a placement confirmation at debug level instead of spamming chat. */
    static void sendDebug(ServerPlayer player, String message) {
        LOGGER.debug("[Builder] {}: {}", player.getScoreboardName(), message);
    }

    /** Sends a message and plays the error sound. */
    static void sendError(ServerPlayer player, String message) {
        sendMessage(player, message);
        playSound(player, ModSounds.ERROR.get());
    }

    /** Plays a sound only for the acting player (ported sound set). */
    private static void playSound(ServerPlayer player, SoundEvent sound) {
        // 26.2 removed Player#playNotifySound; play to the player only via the level.
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                sound, SoundSource.PLAYERS, 1.0f, 1.0f);
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
        int stretched = applyStretch(player, level, axis, positive, oMin, oMax, nMin, nMax);
        if (stretched < 0) {
            return;
        }
        // Keep the region (and the client's selection box) at the new size.
        SelectionStore.setRegion(player, nMin, nMax);
        net.buildertools.network.ModPackets.sendToClient(player.connection.getConnection(), new SelectionSyncPacket(
                true, nMin.getX(), nMin.getY(), nMin.getZ(), nMax.getX(), nMax.getY(), nMax.getZ()));
        sendMessage(player, "Stretched " + stretched + " block(s).");
        playSound(player, ModSounds.FILL.get());
    }

    /**
     * The shared rubber-sheet remap behind the Selection-tool stretch and the Arching stretch:
     * remaps the blocks of the union of the original and new regions proportionally along
     * {@code axis} between the fixed face and the dragged face (stretching duplicates blocks,
     * compressing trims them). Returns the number of blocks touched, or -1 when the operation is
     * invalid (the caller reports the error). Does NOT touch the selection store - the Selection
     * tool path reports its own region sync after calling this.
     */
    private static int applyStretch(ServerPlayer player, Level level, int axis, boolean positive,
                                    BlockPos oMin, BlockPos oMax, BlockPos nMin, BlockPos nMax) {
        BlockPos uMin = new BlockPos(
                Math.min(oMin.getX(), nMin.getX()), Math.min(oMin.getY(), nMin.getY()), Math.min(oMin.getZ(), nMin.getZ()));
        BlockPos uMax = new BlockPos(
                Math.max(oMax.getX(), nMax.getX()), Math.max(oMax.getY(), nMax.getY()), Math.max(oMax.getZ(), nMax.getZ()));

        long volume = (long) (uMax.getX() - uMin.getX() + 1)
                * (uMax.getY() - uMin.getY() + 1)
                * (uMax.getZ() - uMin.getZ() + 1);
        if (volume > MAX_BLOCKS) {
            sendError(player, "Selection is too large (max " + MAX_BLOCKS + " blocks).");
            return -1;
        }
        if (!level.hasChunksAt(uMin, uMax)) {
            sendError(player, "Selection is not fully loaded.");
            return -1;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(uMin)) > MAX_DISTANCE * MAX_DISTANCE
                || player.distanceToSqr(Vec3.atCenterOf(uMax)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Selection is too far away (max " + (int) MAX_DISTANCE + " blocks).");
            return -1;
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
        return changes.size();
    }

    /**
     * Arching stretch (ALT+A + LMB drag): the same rubber-sheet remap as the Selection-tool
     * stretch, but for the placed row tracked by Arching. Unlike {@link #stretchSelection} it
     * does NOT move the Selection tool's stored region - the row lives only in the client's
     * Arching state.
     */
    public static void archStretch(ServerPlayer player, int axis, boolean positive,
                                   BlockPos origMin, BlockPos origMax, BlockPos newMin, BlockPos newMax) {
        if (axis < 0 || axis > 2 || origMin == null || origMax == null || newMin == null || newMax == null) {
            sendError(player, "Invalid stretch parameters.");
            return;
        }
        BlockPos oMin = new BlockPos(Math.min(origMin.getX(), origMax.getX()), Math.min(origMin.getY(), origMax.getY()), Math.min(origMin.getZ(), origMax.getZ()));
        BlockPos oMax = new BlockPos(Math.max(origMin.getX(), origMax.getX()), Math.max(origMin.getY(), origMax.getY()), Math.max(origMin.getZ(), origMax.getZ()));
        BlockPos nMin = new BlockPos(Math.min(newMin.getX(), newMax.getX()), Math.min(newMin.getY(), newMax.getY()), Math.min(newMin.getZ(), newMax.getZ()));
        BlockPos nMax = new BlockPos(Math.max(newMin.getX(), newMax.getX()), Math.max(newMin.getY(), newMax.getY()), Math.max(newMin.getZ(), newMax.getZ()));
        int stretched = applyStretch(player, player.level(), axis, positive, oMin, oMax, nMin, nMax);
        if (stretched < 0) {
            return;
        }
        playSound(player, ModSounds.FILL.get());
    }

    // ------------------------------------------------------------------
    // Arching (ALT+A): turn a stretched row of blocks into an arch of voussoirs
    // ------------------------------------------------------------------

    /**
     * Arching (step 4 of the ALT+A workflow): after the player placed a wall (a straight box of
     * blocks) and stretched it (ALT+A + LMB drag), the client sends the stretched region plus the
     * cell and FACE they clicked to the side of it. The clicked face fixes the arch's frame: the
     * face normal is the arch's depth axis (so a wall face gives a vertical arch, a floor/ceiling
     * face a sideways one), the region's larger projected extent in the face plane is the Span
     * {@code S}, and the click's offset along the in-plane rise direction is the Rise {@code H};
     * the arch radius is {@code R = H/2 + S^2/(8H)}. Every column of the wall (each 1-wide strip
     * along the span) becomes a row of tapered voussoir wedges, and any extra thickness becomes
     * concentric radial layers, so the whole box turns into a curved band with no gaps. The
     * wedges live in the mod's block layer, replacing the vanilla box (whose cells become air).
     * Undo restores the box and removes the wedges.
     */
    public static void archBlocks(ServerPlayer player, BlockPos corner1, BlockPos corner2,
                                  BlockPos click, Direction face) {
        BlockPos min = new BlockPos(
                Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        BlockPos max = new BlockPos(
                Math.max(corner1.getX(), corner2.getX()), Math.max(corner1.getY(), corner2.getY()), Math.max(corner1.getZ(), corner2.getZ()));
        if (click == null || face == null) {
            sendError(player, "Invalid arch click.");
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(click)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "The arch target is too far away (max " + (int) MAX_DISTANCE + " blocks).");
            return;
        }

        int ex = max.getX() - min.getX();
        int ey = max.getY() - min.getY();
        int ez = max.getZ() - min.getZ();
        long volume = (long) (ex + 1) * (ey + 1) * (ez + 1);
        if (volume > MAX_BLOCKS) {
            sendError(player, "Arch: the wall is too large (max " + MAX_BLOCKS + " blocks).");
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        if (!level.hasChunksAt(min, max)) {
            sendError(player, "The arch wall is not fully loaded.");
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(min)) > MAX_DISTANCE * MAX_DISTANCE
                || player.distanceToSqr(Vec3.atCenterOf(max)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "The arch wall is too far away (max " + (int) MAX_DISTANCE + " blocks).");
            return;
        }

        // Every cell of the box must hold a solid block (gaps mean the player never stretched the
        // wall - the stretch fills it). Check before changing anything.
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).isAir() && RotationStore.get(level, pos) == null) {
                sendError(player, "Arch: the wall has gaps - stretch it first to fill them.");
                return;
            }
        }

        // The clicked face fixes the arch's frame: the face normal is the depth axis (v), and the
        // face plane holds the span (u) and the rise (w). The span follows the region's larger
        // projected extent in the face plane, so the arch aligns with the wall however it was
        // built; the click's offset along w from the chord is the Rise (the chord passes through
        // the two span-end cells at the region's centre). This derivation is shared with the
        // client's ghost preview (ArchGeometry.regionArch) so the curve shown while aiming is
        // exactly what this click commits.
        ArchGeometry.RegionArch ra = ArchGeometry.regionArch(min, max, click, face);
        if (ra == null) {
            sendError(player, "Arch: click a block's face clearly to the side of the wall to set the arch height.");
            return;
        }
        ArchGeometry.ArchResult arch = ra.arch();
        BezierGeometry.BezierArch bezier = ra.bezier();
        int count = ra.count();
        double span = ra.span();
        Vec3 center = ra.center();

        // Radial layers: cells offset along w get their own centerline radius R + layer. Only a
        // wall with real thickness can collapse its innermost layer (a wedge whose inner arc
        // would invert); a plain 1-wide row has no layers and keeps its exact pre-wall arch
        // behaviour even for very tight arches. The Bezier wall arch has no radius (the band is
        // a flat 1m-thick curve, so extra thickness just translates the band - no inversion is
        // possible), so the check only applies to the circular bow.
        Vec3 v = arch != null ? arch.u().cross(arch.w()) : bezier.v();
        int maxAbsLayer = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            maxAbsLayer = Math.max(maxAbsLayer,
                    Math.abs((int) Math.round(Vec3.atCenterOf(pos).subtract(center).dot(arch != null ? arch.w() : bezier.w()))));
        }
        if (arch != null && maxAbsLayer > 0 && arch.radius() - maxAbsLayer - 0.5 < 0.5) {
            sendError(player, "Arch: the wall is too thick for this arch height - click farther from the wall (more rise) or shorten the span.");
            return;
        }

        List<BlockChange> changes = archBlocksCore(level, min, max, ra);
        UndoStore.push(player, changes);
        sendMessage(player, "Arched " + changes.size() + " block(s) (span " + (count - 1) + "m).");
        playSound(player, ModSounds.FILL.get());
    }

    /** One wedge of the arch plus the cell that keys it (the cell its centerline midpoint lands
     *  in). Exactly one of {@code arch} (circular bow) and {@code bezier} (Bezier wall arch) is
     *  non-null. Consecutive voussoirs that land in the same cell are merged into one wider wedge
     *  by the commit (the layer is keyed by cell, so two wedges cannot share a cell). */
    private record ArchWedge(ArchBlockData arch, BezierBlockData bezier, BlockPos cell) {
    }

    /** The player-free core of the arch commit: the stretched wall becomes a smooth band of
     *  tapered voussoirs - one ~1m row of wedges per radial layer and depth column of the
     *  region - each keyed by the cell its centerline lands in (so the arch occupies the cells
     *  along the arc, rising above the original row, and the row's middle opens up underneath).
     *  Returns the undo changes. Player-free so the workflow can be verified headlessly (the
     *  client ghost and the commit share {@link ArchGeometry#regionArch}). */
    public static List<BlockChange> archBlocksCore(ServerLevel level, BlockPos min, BlockPos max,
                                            ArchGeometry.RegionArch ra) {
        ArchGeometry.ArchResult arch = ra.arch();
        BezierGeometry.BezierArch bezier = ra.bezier();
        int count = ra.count();
        Vec3 center = ra.center();
        // The depth axis (v) and rise axis (rise) of the arch frame: the circular bow derives
        // them from its circle; the Bezier wall arch carries them directly.
        Vec3 v = arch != null ? arch.u().cross(arch.w()) : bezier.v();
        Vec3 rise = arch != null ? arch.w() : bezier.w();

        // The wedge block states: a wedge keeps the state of the region cell its center lands in;
        // cells the arc rises into (outside the region, previously air) use the first solid
        // region state so the whole arch is made of the wall's block.
        Map<BlockPos, BlockState> cellStates = new HashMap<>();
        BlockState fallback = null;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockPos cell = pos.immutable();
            RotationData layer = RotationStore.get(level, cell);
            BlockState state = layer != null ? layer.state() : level.getBlockState(cell);
            cellStates.put(cell, state);
            if (fallback == null && !state.isAir()) {
                fallback = state;
            }
        }
        if (fallback == null) {
            fallback = Blocks.STONE.defaultBlockState();
        }

        // The radial layers (cells offset along the rise axis w) and depth columns (offset along
        // v) the region spans; each (layer, column) pair is one ring of `count` voussoirs.
        int minW = Integer.MAX_VALUE, maxW = Integer.MIN_VALUE;
        int minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            Vec3 offset = Vec3.atCenterOf(pos).subtract(center);
            minW = Math.min(minW, (int) Math.round(offset.dot(rise)));
            maxW = Math.max(maxW, (int) Math.round(offset.dot(rise)));
            minV = Math.min(minV, (int) Math.round(offset.dot(v)));
            maxV = Math.max(maxV, (int) Math.round(offset.dot(v)));
        }

        List<ArchWedge> wedges = new ArrayList<>();
        // Every cell may hold exactly ONE wedge (the RotationStore is keyed by cell); wedges of
        // different rings whose centreline midpoints round into the same cell would otherwise
        // overwrite each other and vanish - leaving the random 1x1/1x2 gaps. When the preferred
        // cell is already claimed, the wedge is keyed to the nearest still-free cell it actually
        // covers (the cell is only storage - the wedge's own world-space data renders and
        // collides wherever its geometry is).
        Set<BlockPos> usedCells = new HashSet<>();
        for (int wLayer = minW; wLayer <= maxW; wLayer++) {
            for (int vCol = minV; vCol <= maxV; vCol++) {
                List<ArchWedge> ringWedges = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    // The Bezier wall arch keys one ~1m voussoir per curve step, each shifted into
                    // its depth column and rise layer (translate the whole curve); the circular
                    // bow uses the shared-radius concentric-ring layout (radius + layer offset).
                    BlockPos cell;
                    if (bezier != null) {
                        Vec3 offset = v.scale(vCol).add(rise.scale(wLayer));
                        BezierBlockData data = BezierGeometry.blockData(bezier, i, offset);
                        cell = cellOf(BezierGeometry.wedgeCenter(data));
                        if (!ringWedges.isEmpty()) {
                            ArchWedge last = ringWedges.get(ringWedges.size() - 1);
                            if (last.cell().equals(cell)) {
                                // The next voussoir lands in the same cell: merge it into the
                                // previous wedge (same ring - just a longer curve slice).
                                BezierBlockData d = last.bezier();
                                ringWedges.set(ringWedges.size() - 1, new ArchWedge(null,
                                        BezierGeometry.extend(d, data.t1() - d.t1()), cell));
                                continue;
                            }
                        }
                        cell = freeCellFor(data, cell, usedCells);
                        usedCells.add(cell);
                        ringWedges.add(new ArchWedge(null, data, cell));
                        continue;
                    }
                    ArchBlockData base = ArchGeometry.blockData(arch, i, count);
                    // Offset the wedge into its depth column (shift the circle center along v)
                    // and radial layer (its own centerline radius), so columns and layers tile
                    // without gaps.
                    ArchBlockData data = new ArchBlockData(
                            base.ox() + v.x * vCol, base.oy() + v.y * vCol, base.oz() + v.z * vCol,
                            base.ux(), base.uy(), base.uz(),
                            base.wx(), base.wy(), base.wz(),
                            base.thetaStart(), base.deltaTheta(), base.radius() + wLayer);
                    cell = cellOf(ArchGeometry.wedgeCenter(data));
                    if (!ringWedges.isEmpty()) {
                        ArchWedge last = ringWedges.get(ringWedges.size() - 1);
                        if (last.cell().equals(cell)) {
                            // The next voussoir lands in the same cell: merge it into the previous
                            // wedge (same ring, so same radius/depth - just a wider angular slice).
                            ArchBlockData d = last.arch();
                            ringWedges.set(ringWedges.size() - 1, new ArchWedge(new ArchBlockData(
                                    d.ox(), d.oy(), d.oz(),
                                    d.ux(), d.uy(), d.uz(),
                                    d.wx(), d.wy(), d.wz(),
                                    d.thetaStart(), d.deltaTheta() + data.deltaTheta(), d.radius()),
                                    null, cell));
                            continue;
                        }
                    }
                    cell = freeCellFor(data, cell, usedCells);
                    usedCells.add(cell);
                    ringWedges.add(new ArchWedge(data, null, cell));
                }
                wedges.addAll(ringWedges);
            }
        }

        // Undo: every region cell is restored (the row is replaced by the arch - cells under the
        // arc open up, cells on the arc hold a wedge), and wedge cells OUTSIDE the region (the
        // arc rose above the row) are captured too so undo clears them as well.
        Set<BlockPos> wedgeCells = new HashSet<>();
        for (ArchWedge wd : wedges) {
            wedgeCells.add(wd.cell());
        }
        List<BlockChange> changes = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockPos cell = pos.immutable();
            changes.add(capture(level, cell, wedgeCells.contains(cell) ? cell : null));
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            if (RotationStore.get(level, cell) != null) {
                RotationStore.remove(level, cell);
            }
        }
        for (ArchWedge wd : wedges) {
            if (wd.cell().getX() >= min.getX() && wd.cell().getX() <= max.getX()
                    && wd.cell().getY() >= min.getY() && wd.cell().getY() <= max.getY()
                    && wd.cell().getZ() >= min.getZ() && wd.cell().getZ() <= max.getZ()) {
                continue;
            }
            if (wedgeCells.contains(wd.cell())) {
                changes.add(capture(level, wd.cell(), wd.cell()));
                wedgeCells.remove(wd.cell());
            }
            level.setBlock(wd.cell(), Blocks.AIR.defaultBlockState(), 3);
        }
        for (ArchWedge wd : wedges) {
            BlockState state = cellStates.getOrDefault(wd.cell(), fallback);
            if (wd.bezier() != null) {
                RotationStore.set(level, wd.cell(), new RotationData(state, 0.0f, 0.0f, false,
                        BezierGeometry.wedgeCenter(wd.bezier()), null, null, wd.bezier()));
            } else {
                RotationStore.set(level, wd.cell(), new RotationData(state, 0.0f, 0.0f, false,
                        ArchGeometry.wedgeCenter(wd.arch()), wd.arch(), null, null));
            }
        }
        return changes;
    }

    // ------------------------------------------------------------------
    // Ellipse (ALT+E): turn the placed region into a closed elliptical ring
    // ------------------------------------------------------------------

    /**
     * Ellipse (the ALT+E mechanic): the placed region becomes a complete, closed loop of tapered
     * voussoirs lying in the plane of the clicked block face. The face's in-plane axes are the
     * ring's semi-axes - the region's projected cell-center extents give {@code a} and
     * {@code b} so the ring's outer edge sits flush with the region's faces - and the region's
     * thickness along the face normal is the ring's depth: each depth cell becomes a concentric
     * ring layer. Every voussoir is ~1m wide at the centerline (the ring is split into equal
     * arc-length steps, {@code N = round(perimeter)}), so the loop tiles with no gaps. The wedges
     * live in the mod's block layer, replacing the region's vanilla blocks (whose cells become
     * air). Undo restores the region and removes the wedges.
     */
    public static void ellipseBlocks(ServerPlayer player, BlockPos corner1, BlockPos corner2,
                                     BlockPos click, Direction face) {
        BlockPos min = new BlockPos(
                Math.min(corner1.getX(), corner2.getX()), Math.min(corner1.getY(), corner2.getY()), Math.min(corner1.getZ(), corner2.getZ()));
        BlockPos max = new BlockPos(
                Math.max(corner1.getX(), corner2.getX()), Math.max(corner1.getY(), corner2.getY()), Math.max(corner1.getZ(), corner2.getZ()));
        if (click == null || face == null) {
            sendError(player, "Invalid ellipse click.");
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(click)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "The ellipse target is too far away (max " + (int) MAX_DISTANCE + " blocks).");
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(min)) > MAX_DISTANCE * MAX_DISTANCE
                || player.distanceToSqr(Vec3.atCenterOf(max)) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "The ellipse region is too far away (max " + (int) MAX_DISTANCE + " blocks).");
            return;
        }

        int ex = max.getX() - min.getX();
        int ey = max.getY() - min.getY();
        int ez = max.getZ() - min.getZ();
        long volume = (long) (ex + 1) * (ey + 1) * (ez + 1);
        if (volume > MAX_BLOCKS) {
            sendError(player, "Ellipse: the region is too large (max " + MAX_BLOCKS + " blocks).");
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        if (!level.hasChunksAt(min, max)) {
            sendError(player, "The ellipse region is not fully loaded.");
            return;
        }

        // The clicked face fixes the ring's frame: the face plane holds the ring (u = right,
        // w = up), the face normal is the depth (v). The region's projected cell-center extents
        // give the semi-axes a/b and the number of depth layers.
        FaceFrame frame = FaceFrame.of(click, face);
        Vec3 u = frame.right();
        Vec3 w = frame.up();
        double extentU = cellExtent(min, max, u);
        double extentW = cellExtent(min, max, w);
        if (extentU < 1.0 || extentW < 1.0) {
            sendError(player, "Ellipse: the region needs at least 2 blocks in two directions.");
            return;
        }
        double a = extentU / 2.0;
        double b = extentW / 2.0;

        // Every cell of the region must be solid (the workflow: place the wall, then hit ALT+E).
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).isAir() && RotationStore.get(level, pos) == null) {
                sendError(player, "Ellipse: the wall has gaps - fill them first.");
                return;
            }
        }

        // The 1m-thick ring must be able to close: the inner edge (offset 0.5m inward) needs
        // positive semi-axes and its curvature at the tips (b^2/a) must stay above the pinch
        // limit, or adjacent voussoirs would cross at the narrow ends.
        if (a - 0.5 < 0.5 || b - 0.5 < 0.5) {
            sendError(player, "Ellipse: the region is too narrow to form a ring - make it at least 2 blocks in both directions.");
            return;
        }
        if (b * b / a < EllipseGeometry.MIN_CURVATURE) {
            sendError(player, "Ellipse: the region is too flat for a closed ring - make it wider or taller.");
            return;
        }

        // The ring geometry itself (centerline semi-axes, depth layers, arc-length segmentation)
        // comes from the shared derivation, so the commit and the client's ghost preview always
        // agree.
        EllipseGeometry.RegionEllipse re = EllipseGeometry.regionEllipse(min, max, click, face);
        if (re == null) {
            sendError(player, "Ellipse: the region cannot form a ring.");
            return;
        }
        EllipseGeometry.EllipseResult ellipse = re.ellipse();
        Vec3 v = ellipse.v();
        int layers = re.layers();

        // Split the ring into segments: consecutive voussoirs that land in the same cell merge
        // into one wider wedge (the layer is keyed by cell, so two wedges cannot share a cell).
        // The segment structure is identical for every layer (layers only shift the depth).
        int count = ellipse.count();
        List<EllipseGeometry.Segment> segs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            EllipseBlockData d = EllipseGeometry.blockData(ellipse, i, 0);
            BlockPos cell = cellOf(EllipseGeometry.wedgeCenter(d));
            if (!segs.isEmpty() && segs.get(segs.size() - 1).cell().equals(cell)) {
                EllipseGeometry.Segment last = segs.remove(segs.size() - 1);
                segs.add(new EllipseGeometry.Segment(EllipseGeometry.extend(last.data(), d.deltaTheta()), cell));
            } else {
                segs.add(new EllipseGeometry.Segment(d, cell));
            }
        }
        // Wrap-around: the last wedge and the first wedge are adjacent across 2*pi. When they
        // landed in the same tip cell they must merge into one wedge spanning the seam.
        if (segs.size() > 1) {
            EllipseGeometry.Segment first = segs.get(0);
            EllipseGeometry.Segment last = segs.get(segs.size() - 1);
            if (last.cell().equals(first.cell())) {
                segs.remove(segs.size() - 1);
                EllipseBlockData d = first.data();
                EllipseBlockData merged = new EllipseBlockData(
                        d.cx(), d.cy(), d.cz(),
                        d.ux(), d.uy(), d.uz(),
                        d.wx(), d.wy(), d.wz(),
                        d.a(), d.b(),
                        last.data().thetaStart(),
                        d.thetaStart() + d.deltaTheta() + Math.PI * 2.0 - last.data().thetaStart());
                segs.set(0, new EllipseGeometry.Segment(merged, first.cell()));
            }
        }

        // The cells the ring's wedges will occupy (all inside the region: the outer edge is flush
        // with the region's faces). Undo must restore those cells' vanilla blocks AND remove the
        // wedge the ellipse puts into the layer keyed by the same cell.
        Set<BlockPos> wedgeCells = new HashSet<>();
        for (EllipseGeometry.Segment seg : segs) {
            for (int l = 0; l < layers; l++) {
                wedgeCells.add(cellOf(EllipseGeometry.wedgeCenter(segmentData(seg.data(), v, l, layers))));
            }
        }

        // Keep each region cell's real block state (its texture, slab half...) so the ring's
        // wedges match the wall the player placed.
        Map<BlockPos, BlockState> cellStates = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockPos cell = pos.immutable();
            RotationData layer = RotationStore.get(level, cell);
            cellStates.put(cell, layer != null ? layer.state() : level.getBlockState(cell));
        }

        List<BlockChange> changes = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockPos cell = pos.immutable();
            // Undo restores this cell's vanilla block AND removes the wedge (if any) that the
            // ellipse puts into the layer keyed by this cell.
            changes.add(capture(level, cell, wedgeCells.contains(cell) ? cell : null));
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            if (RotationStore.get(level, cell) != null) {
                RotationStore.remove(level, cell);
            }
        }

        int wedges = 0;
        for (EllipseGeometry.Segment seg : segs) {
            for (int l = 0; l < layers; l++) {
                EllipseBlockData data = segmentData(seg.data(), v, l, layers);
                Vec3 wedgeCenter = EllipseGeometry.wedgeCenter(data);
                BlockPos cell = cellOf(wedgeCenter);
                BlockState state = cellStates.getOrDefault(cell, level.getBlockState(cell));
                RotationStore.set(level, cell, new RotationData(state, 0.0f, 0.0f, false, wedgeCenter, null, data, null));
                wedges++;
            }
        }

        UndoStore.push(player, changes);
        sendMessage(player, "Ellipsed " + wedges + " block(s) (" + count + "m around the loop).");
        playSound(player, ModSounds.FILL.get());
    }

    /** The wedge data of a ring layer: the base wedge shifted {@code layerOff} along the depth
     *  axis {@code v} so the {@code layers} rings sweep the region's full depth extent. */
    private static EllipseBlockData segmentData(EllipseBlockData base, Vec3 v, int layer, int layers) {
        double layerOff = layer - (layers - 1) / 2.0;
        return new EllipseBlockData(
                base.cx() + v.x * layerOff, base.cy() + v.y * layerOff, base.cz() + v.z * layerOff,
                base.ux(), base.uy(), base.uz(),
                base.wx(), base.wy(), base.wz(),
                base.a(), base.b(), base.thetaStart(), base.deltaTheta());
    }

    /** The cell containing the given world-space point. */
    private static BlockPos cellOf(Vec3 point) {
        return new BlockPos((int) Math.floor(point.x), (int) Math.floor(point.y), (int) Math.floor(point.z));
    }

    /**
     * The storage cell of a wedge: its centreline-midpoint cell if still free, otherwise the
     * nearest free cell it actually covers (cells of its 8 corners first, then an expanding
     * cube around the midpoint). The cell merely keys the wedge in the per-cell RotationStore -
     * rendering and collision use the wedge's own world-space data - so re-keying cannot change
     * the arch, it only prevents two wedges from overwriting each other (the random gaps).
     * {@code used} is mutated: the returned cell is claimed.
     */
    private static BlockPos freeCellFor(BezierBlockData data, BlockPos preferred, Set<BlockPos> used) {
        if (!used.contains(preferred)) {
            return preferred;
        }
        Vec3 center = BezierGeometry.wedgeCenter(data);
        List<BlockPos> candidates = new ArrayList<>();
        for (Vec3 corner : BezierGeometry.wedgeVertices(data)) {
            candidates.add(cellOf(corner));
        }
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) {
                            continue;
                        }
                        candidates.add(preferred.offset(dx, dy, dz));
                    }
                }
            }
        }
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            if (used.contains(candidate)) {
                continue;
            }
            double dist = Vec3.atCenterOf(candidate).distanceToSqr(center);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        if (best != null) {
            return best;
        }
        // Pathological density: nothing free nearby. Keep the preferred cell - the wedge is
        // still rendered and collided (its data is world-space); only the per-cell store
        // overwrites, which is preferable to silently dropping a wedge.
        return preferred;
    }

    /** See {@link #freeCellFor(BezierBlockData, BlockPos, Set)}; the circular bow variant. */
    private static BlockPos freeCellFor(ArchBlockData data, BlockPos preferred, Set<BlockPos> used) {
        if (!used.contains(preferred)) {
            return preferred;
        }
        Vec3 center = ArchGeometry.wedgeCenter(data);
        List<BlockPos> candidates = new ArrayList<>();
        for (Vec3 corner : ArchGeometry.wedgeVertices(data)) {
            candidates.add(cellOf(corner));
        }
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) {
                            continue;
                        }
                        candidates.add(preferred.offset(dx, dy, dz));
                    }
                }
            }
        }
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (BlockPos candidate : candidates) {
            if (used.contains(candidate)) {
                continue;
            }
            double dist = Vec3.atCenterOf(candidate).distanceToSqr(center);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        if (best != null) {
            return best;
        }
        return preferred;
    }

    /** The span (max - min) of the region's cell centres projected onto the unit vector. */
    private static double cellExtent(BlockPos min, BlockPos max, Vec3 axis) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    double px = min.getX() + 0.5 + dx * (max.getX() - min.getX());
                    double py = min.getY() + 0.5 + dy * (max.getY() - min.getY());
                    double pz = min.getZ() + 0.5 + dz * (max.getZ() - min.getZ());
                    double p = px * axis.x + py * axis.y + pz * axis.z;
                    lo = Math.min(lo, p);
                    hi = Math.max(hi, p);
                }
            }
        }
        return hi - lo;
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
            if (level.getRandom().nextDouble() > 0.5) {
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

        ListTag entries = clipboard.getListOrEmpty("entries");
        List<BlockChange> changes = new ArrayList<>();
        int count = 0;
        int skipped = 0;
        for (Tag tag : entries) {
            CompoundTag entry = (CompoundTag) tag;
            BlockPos pos = anchor.offset(entry.getIntOr("x", 0), entry.getIntOr("y", 0), entry.getIntOr("z", 0));
            if (!level.hasChunkAt(pos)) {
                skipped++;
                continue;
            }
            BlockState state;
            try {
                state = BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK),
                        entry.getStringOr("state", "minecraft:air"), false).blockState();
            } catch (Exception ex) {
                skipped++;
                continue;
            }
            changes.add(capture(level, pos.immutable()));
            level.setBlock(pos, state, 3);
            if (entry.contains("nbt")) {
                BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, entry.getCompoundOrEmpty("nbt"), level.registryAccess());
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
     * Places a NEW rotated block into the mod's block layer: the held vanilla block goes into the
     * layer (the block itself stays the block it is - same shading, breaking, drops), the vanilla
     * cell stays AIR, and the layer entry carries the state, rotation and the exact world-space
     * model center {@code (cx, cy, cz)} (fractional for blocks snapped onto a rotated neighbor's
     * grid). Re-rotating an already placed block updates its entry in place, keeping its center.
     */
    public static void handleBlockRotation(ServerPlayer player, BlockPos cell,
                                           double cx, double cy, double cz,
                                           float yaw, float pitch, boolean billboard,
                                           Direction slabDirection, boolean mergeDouble) {
        if (player.distanceToSqr(cx, cy, cz) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Position is too far away.");
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!level.hasChunkAt(cell)) {
            sendError(player, "Area is not loaded.");
            return;
        }
        RotationData existing = RotationStore.get(level, cell);
        if (existing != null) {
            // Re-rotate the block already in the layer, strictly in place: its exact model
            // center stays and only the angles change. R-rotating a slab (slabDirection null)
            // keeps its occupied half fixed; a click whose landing box resolves back onto the
            // same cell (e.g. a diagonal-rotated neighbor) carries a direction and re-places the
            // slab into that half (slab-aware re-rotation) - non-slab states are untouched.
            Vec3 c = existing.center(cell);
            BlockState state = existing.state();
            if (mergeDouble) {
                // Same-material inner-face click: fill the block - the rotated slab becomes a
                // full double slab in place (its exact model center and angles stay).
                state = io.github.favasur.fullslabs.block.SlabVertical.doubleSlab(state);
            } else if (slabDirection != null) {
                state = io.github.favasur.fullslabs.block.SlabVertical.applyDirection(state, slabDirection);
            }
            RotationStore.set(level, cell, new RotationData(state, yaw, pitch, billboard, c));
            recordOffGridPlacement(player, cell);
            sendDebug(player, "Rotated block (yaw " + Math.round(yaw) + ", pitch " + Math.round(pitch)
                    + (billboard ? ", billboard" : "") + ").");
            level.playSound(null, cell, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 1.0f);
            return;
        }
        // New placement: the held block into the cell, then record its rotation.
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            sendError(player, "Hold a block in your main hand to place.");
            return;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        state = slabDirection != null
                ? io.github.favasur.fullslabs.block.SlabVertical.applyDirection(state, slabDirection)
                : net.buildertools.util.FullSlabsCompat.normalize(state);
        VoxelShape shape = state.getCollisionShape(level, BlockPos.ZERO);
        if (shape.isEmpty()) {
            shape = Shapes.block(); // blocks without a collision shape (torch, rail, ...) act as a full cell
        }
        if (player.getBoundingBox().intersects(OffGridTransform.boxAround(cx, cy, cz, yaw, pitch, shape.bounds()))) {
            sendError(player, "You're in the way - move back first.");
            return;
        }
        // The new rotated model may not penetrate any OTHER rotated block (layer or legacy
        // entity) - touching flush along a rotated face is fine, cutting through is not.
        if (penetratesRotatedBlock(level, cell, cx, cy, cz, yaw, pitch, shape)) {
            if (LOGGER.isDebugEnabled()) {
                AABB box = OffGridTransform.boxAround(cx, cy, cz, yaw, pitch, shape.bounds());
                LOGGER.debug("[Builder] {}: REJECTED cut-through cell={} state={} center=({},{},{}) yaw={} box=({},{},{})-({},{},{})",
                        player.getScoreboardName(), cell, state, cx, cy, cz, yaw, pitch,
                        box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
            }
            sendError(player, "That would cut through another rotated block.");
            return;
        }
        // The vanilla block sitting in the cell is replaced (dropped in survival), then the cell
        // becomes air - the rotated block lives in the mod's layer from now on.
        if (!level.getBlockState(cell).isAir()) {
            level.destroyBlock(cell, !player.getAbilities().instabuild);
        }
        RotationStore.set(level, cell, new RotationData(state, yaw, pitch, billboard,
                new Vec3(cx, cy, cz)));
        // Remember the cell: the vanilla use-item packet for the same click may arrive next,
        // and the server-side right-click handler uses this record to cancel the duplicate.
        recordOffGridPlacement(player, cell);
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
        }
        sendDebug(player, "Placed block (yaw " + Math.round(yaw) + ", pitch " + Math.round(pitch)
                + (billboard ? ", billboard" : "") + ").");
        level.playSound(null, cell, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    /**
     * True when a new rotated model centered at {@code (cx, cy, cz)} with the given rotation would
     * penetrate another rotated block (layer entry or legacy entity). Touching face-to-face is
     * allowed (the SAT check uses a small tolerance), so flush placements pass.
     */
    private static boolean penetratesRotatedBlock(ServerLevel level, BlockPos selfCell,
                                                  double cx, double cy, double cz,
                                                  float yaw, float pitch, VoxelShape shape) {
        AABB around = OffGridTransform.boxAround(cx, cy, cz, yaw, pitch, shape.bounds()).inflate(0.5);
        for (Map.Entry<BlockPos, RotationData> e : RotationStore.getInBox(level, around)) {
            if (e.getKey().equals(selfCell)) {
                continue;
            }
            RotationData other = e.getValue();
            Vec3 oc = other.center(e.getKey());
            VoxelShape otherShape = other.state().getCollisionShape(level, BlockPos.ZERO);
            if (OffGridTransform.modelsOverlap(
                    cx, cy, cz, yaw, pitch, shape,
                    oc.x, oc.y, oc.z, other.yaw(), other.pitch(), otherShape)) {
                return true;
            }
        }
        for (OffGridBlockEntity other : level.getEntitiesOfClass(OffGridBlockEntity.class, around)) {
            Vec3 oc = other.modelCenter();
            VoxelShape otherShape = other.getRepresentedState().getCollisionShape(level, BlockPos.ZERO);
            if (OffGridTransform.modelsOverlap(
                    cx, cy, cz, yaw, pitch, shape,
                    oc.x, oc.y, oc.z, other.getPlacementYaw(), other.getPlacementPitch(), otherShape)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Breaks the rotated block in the given cell of the mod's layer like a normal block: drops the
     * block's item in survival and removes the entry. The vanilla cell is already air.
     */
    public static void handleFreeBlockBreak(ServerPlayer player, BlockPos cell) {
        ServerLevel level = (ServerLevel) player.level();
        RotationData data = RotationStore.get(level, cell);
        if (data == null) {
            return;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(cell)) > MAX_DISTANCE * MAX_DISTANCE) {
            return;
        }
        BlockState state = data.state();
        if (!player.getAbilities().instabuild) {
            if (state != null && !state.isAir()) {
                level.addFreshEntity(new ItemEntity(level,
                        cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5,
                        new ItemStack(state.getBlock())));
            }
        }
        RotationStore.remove(level, cell);
        if (state != null && !state.isAir()) {
            level.globalLevelEvent(2001, cell, Block.getId(state));
        }
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
            sendDebug(player, "Rotated legacy block (yaw " + Math.round(yaw) + ", pitch " + Math.round(pitch) + ").");
            level.playSound(null, BlockPos.containing(cx, cy, cz), state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0f, 1.0f);
            return;
        }
        handleBlockRotation(player, BlockPos.containing(cx, cy, cz), cx, cy, cz, yaw, pitch, billboard, null, false);
    }

    /** Spawns a legacy off-grid entity pair (display + collidable entity) for old worlds. */
    private static void spawnLegacyPair(Level level, double cx, double cy, double cz, BlockState state,
                                        float yaw, float pitch, boolean billboard) {
        Display.BlockDisplay display = new Display.BlockDisplay(net.minecraft.world.entity.EntityTypes.BLOCK_DISPLAY, level);
        ((BlockDisplayAccessor) (Object) display).buildertools$setBlockState(state);
        display.setPos(cx - 0.5, cy - 0.5, cz - 0.5);
        ((DisplayAccessor) (Object) display).buildertools$setTransformation(OffGridTransform.transformation(yaw, pitch));
        ((DisplayAccessor) (Object) display).buildertools$setBillboardConstraints(billboard
                ? Display.BillboardConstraints.CENTER
                : Display.BillboardConstraints.FIXED);
        display.addTag(OFF_GRID_TAG);
        level.addFreshEntity(display);

        OffGridBlockEntity block = ModEntities.OFF_GRID_BLOCK.get()
                .create(level, EntitySpawnReason.COMMAND);
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
        VoxelShape cubeShape = Shapes.block();
        for (OffGridBlockEntity other : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(cell).inflate(1.5))) {
            if (!other.entityTags().contains(OFF_GRID_TAG)) {
                continue;
            }
            if (OffGridTransform.modelsOverlap(
                    cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, 0.0f, 0.0f, cubeShape,
                    other.modelCenter().x, other.modelCenter().y, other.modelCenter().z,
                    other.getPlacementYaw(), other.getPlacementPitch(),
                    other.getRepresentedState().getCollisionShape(level, BlockPos.ZERO))) {
                return true;
            }
        }
        return false;
    }

    /** Removes the off-grid display at the given model center (dropping its item in survival) and plays a break sound. */
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
        BlockState state = block.getRepresentedState();
        if (!player.getAbilities().instabuild) {
            if (!state.isAir()) {
                level.addFreshEntity(new ItemEntity(level,
                        cx, cy + 0.25, cz,
                        new ItemStack(state.getBlock())));
            }
        }
        block.discardWithDisplay();
        if (!state.isAir()) {
            level.globalLevelEvent(2001, BlockPos.containing(cx, cy, cz), Block.getId(state));
        }
    }

    /** Finds the solid off-grid block whose model center is nearest the given point, or null. */
    public static OffGridBlockEntity findOffGrid(Level level, double x, double y, double z) {
        double best = 0.36; // within 0.6 blocks
        OffGridBlockEntity bestBlock = null;
        for (OffGridBlockEntity block : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(x - 0.75, y - 0.75, z - 0.75, x + 0.75, y + 0.75, z + 0.75))) {
            if (!block.entityTags().contains(OFF_GRID_TAG)) {
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

    /** Finds the solid off-grid block occupying the given grid cell (center inside it), or null. */
    public static OffGridBlockEntity findOffGrid(Level level, BlockPos pos) {
        for (OffGridBlockEntity block : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                        pos.getX() + 2, pos.getY() + 2, pos.getZ() + 2))) {
            if (block.entityTags().contains(OFF_GRID_TAG) && block.cell().equals(pos)) {
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
                Entity display = ((ServerLevel) player.level()).getEntity(uuid);
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
        if (entity instanceof Painting painting && painting instanceof FlexiblePaintingAccess access && !headOnly) {
            // 26.2 FlexiblePainting replaced arbitrary visual rotation with a surface type
            // (wall/floor/ceiling). Map the Entity Tool's absolute pitch onto it: steep pitch
            // lays the artwork flat on the floor/ceiling, anything else stays on the wall.
            if (pitch > 45.0f) {
                access.flexiblePainting$setSurfaceType(FlexiblePaintingAccess.SurfaceType.FLOOR);
            } else if (pitch < -45.0f) {
                access.flexiblePainting$setSurfaceType(FlexiblePaintingAccess.SurfaceType.CEILING);
            } else {
                access.flexiblePainting$setSurfaceType(FlexiblePaintingAccess.SurfaceType.WALL);
            }
        }
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
    public static void spawnEntity(ServerPlayer player, Identifier typeId, double x, double y, double z) {
        if (player.distanceToSqr(x, y, z) > MAX_DISTANCE * MAX_DISTANCE) {
            sendError(player, "Position is too far away.");
            return;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(typeId).map(Holder::value).orElse(null);
        if (type == null || type == net.minecraft.world.entity.EntityTypes.PLAYER) {
            sendError(player, "Unknown entity type.");
            return;
        }
        ServerLevel serverLevel = (ServerLevel) player.level();
        Entity entity = type.create(serverLevel, EntitySpawnReason.COMMAND);
        if (entity == null) {
            sendError(player, "Could not spawn entity.");
            return;
        }
        entity.setPos(x, y, z);
        entity.setYRot(player.getYRot());
        entity.setXRot(0.0f);
        if (entity instanceof Mob mob) {
            mob.finalizeSpawn(serverLevel,
                    serverLevel.getCurrentDifficultyAt(BlockPos.containing(x, y, z)),
                    EntitySpawnReason.COMMAND, null);
        }
        serverLevel.addFreshEntity(entity);
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
        ServerLevel serverLevel = (ServerLevel) player.level();

        // 26.2 entity save/load is a codec-backed ValueOutput/ValueInput stream. Serialize the
        // entity into a compound tag, drop its UUID (the level assigns a fresh one), then
        // deserialize it back into a new instance of the same type.
        CompoundTag data;
        try {
            net.minecraft.util.ProblemReporter reporter =
                    net.minecraft.util.ProblemReporter.DISCARDING;
            net.minecraft.world.level.storage.TagValueOutput out =
                    net.minecraft.world.level.storage.TagValueOutput.createWithContext(reporter, serverLevel.registryAccess());
            entity.saveWithoutId(out);
            data = out.buildResult();
        } catch (Throwable t) {
            sendMessage(player, "Could not duplicate entity.");
            return;
        }
        data.remove("UUID");
        data.remove("UUIDMost");
        data.remove("UUIDLeast");

        Entity copy = EntityType.create(entity.getType(),
                        net.minecraft.world.level.storage.TagValueInput.create(
                                net.minecraft.util.ProblemReporter.DISCARDING, serverLevel.registryAccess(), data),
                        serverLevel, EntitySpawnReason.COMMAND)
                .orElse(null);
        if (copy == null) {
            sendMessage(player, "Could not duplicate entity.");
            return;
        }
        copy.setPos(entity.getX() + 0.5, entity.getY(), entity.getZ() + 0.5);
        copy.setYRot(entity.getYRot());
        copy.setXRot(entity.getXRot());
        serverLevel.addFreshEntity(copy);
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
        net.minecraft.world.item.component.TypedEntityData<?> data =
                held.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
        return data != null ? data.getUnsafe() : null;
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

    /** Players who have No Clip enabled. {@code Player.tick()} resets {@code noPhysics} every
     *  tick (it is derived from spectator mode), so each tick we re-apply it for these players. */
    private static final Set<UUID> NO_CLIP_PLAYERS = new HashSet<>();

    public static void applyWorldSettings(ServerPlayer player, long timeOfDay, Boolean pauseTime, int weather,
                                          Boolean smoothTerrain) {
        ServerLevel level = (ServerLevel) player.level();
        if (timeOfDay >= 0) {
            // 26.2 replaced the per-level day time with the world-clock system.
            level.dimensionTypeRegistration().value().defaultClock().ifPresent(clock ->
                    level.getServer().clockManager().setTotalTicks(clock, timeOfDay));
        }
        if (pauseTime != null) {
            // Pause Time = stop the day/night cycle the vanilla way, so everything else keeps
            // running (mobs, redstone) while the sun freezes in place.
            level.getGameRules().set(
                    net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,
                    !pauseTime, level.getServer());
        }
        if (weather != net.buildertools.network.packet.WorldSettingsPacket.SKIP_WEATHER) {
            switch (weather) {
                case 0 -> level.getServer().setWeatherParameters(6000, 0, false, false);
                case 1 -> level.getServer().setWeatherParameters(0, 6000, true, false);
                case 2 -> level.getServer().setWeatherParameters(0, 6000, true, true);
                default -> {
                }
            }
        }
        if (smoothTerrain != null) {
            // Smooth Terrain world setting: applies the bundled Surface Nets meshing on every
            // client (and the integrated/local server) and re-meshes the chunks.
            io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl.Server.setEnabled(smoothTerrain);
            net.buildertools.network.packet.SmoothTerrainTogglePacket packet =
                    new net.buildertools.network.packet.SmoothTerrainTogglePacket(smoothTerrain,
                            SmoothTerrainWorldRules.smoothness());
            for (ServerPlayer p : level.players()) {
                net.buildertools.network.ModPackets.sendToClient(p.connection.getConnection(), packet);
            }
        }
        sendMessage(player, "Updated world settings.");
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
        return capture(level, pos, null);
    }

    /** Captures a block whose change also REMOVES the mod-layer entry at {@code layerCell} on
     *  undo (arching replaces the vanilla block with a layer wedge keyed by the same cell). */
    static BlockChange capture(Level level, BlockPos pos, BlockPos layerCell) {
        BlockState state = level.getBlockState(pos);
        CompoundTag blockEntityNbt = null;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            blockEntityNbt = blockEntity.saveWithFullMetadata(level.registryAccess());
        }
        return new BlockChange(pos.immutable(), state, blockEntityNbt,
                layerCell != null ? List.of(layerCell.immutable()) : List.of());
    }

    /** Restores previously captured block states (undo). Layer entries the change created (arch
     *  wedges) are removed alongside, so undo restores the vanilla row AND clears the arch. */
    public static void applyChanges(Level level, List<BlockChange> changes) {
        for (BlockChange change : changes) {
            level.setBlock(change.pos(), change.state(), 3);
            if (change.blockEntityNbt() != null) {
                BlockEntity blockEntity = BlockEntity.loadStatic(change.pos(), change.state(), change.blockEntityNbt(), level.registryAccess());
                if (blockEntity != null) {
                    level.setBlockEntity(blockEntity);
                }
            }
            for (BlockPos layerCell : change.layerCells()) {
                if (level instanceof ServerLevel serverLevel) {
                    RotationStore.remove(serverLevel, layerCell);
                }
            }
        }
    }
}
