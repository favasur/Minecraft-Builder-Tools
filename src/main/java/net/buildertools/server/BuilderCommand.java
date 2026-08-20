package net.buildertools.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.network.packet.SelectionSyncPacket;
import net.buildertools.registry.ModItems;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * A WorldEdit-style command set that applies to the selection the player made with the Selection
 * Tool. The selection is kept server-side (synced by the client) so all of these work in single
 * player and on servers:
 * {@code set, replace, walls, outline, hollow, faces, overlay, center, copy, cut, paste, move,
 * stack, expand, contract, shift, undo, redo, clear, clearinventory, clearentities, pos1, pos2,
 * sel, wand}.
 */
public final class BuilderCommand {
    private static final int MAX_BLOCKS = BuilderServerHandler.MAX_BLOCKS;
    private static final double MAX_DISTANCE = BuilderServerHandler.MAX_DISTANCE;

    private BuilderCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        CommandBuildContext buildContext = event.getBuildContext();

        // Each command is registered at the top level (/set, /replace, /copy, ...).
        dispatcher.register(Commands.literal("wand").executes(ctx -> wand(ctx)));
        dispatcher.register(Commands.literal("tools").executes(ctx -> tools(ctx)));
        dispatcher.register(Commands.literal("pos1").executes(ctx -> pos1(ctx)));
        dispatcher.register(Commands.literal("pos2").executes(ctx -> pos2(ctx)));
        dispatcher.register(Commands.literal("sel").executes(ctx -> sel(ctx)));
        dispatcher.register(Commands.literal("set").then(blockArg(buildContext, "block").executes(ctx -> setBlocks(ctx, "block"))));
        dispatcher.register(Commands.literal("replace")
                .then(Commands.argument("from", BlockStateArgument.block(buildContext))
                        .then(blockArg(buildContext, "to").executes(ctx -> replace(ctx, "from", "to"))))
                .then(blockArg(buildContext, "to").executes(ctx -> replaceAll(ctx, "to"))));
        dispatcher.register(Commands.literal("walls").then(blockArg(buildContext, "block").executes(ctx -> walls(ctx, "block"))));
        dispatcher.register(Commands.literal("outline").then(blockArg(buildContext, "block").executes(ctx -> outline(ctx, "block"))));
        dispatcher.register(Commands.literal("hollow").then(blockArg(buildContext, "block").executes(ctx -> hollow(ctx, "block"))));
        dispatcher.register(Commands.literal("faces").then(blockArg(buildContext, "block").executes(ctx -> faces(ctx, "block"))));
        dispatcher.register(Commands.literal("overlay").then(blockArg(buildContext, "block").executes(ctx -> overlay(ctx, "block"))));
        dispatcher.register(Commands.literal("center").then(blockArg(buildContext, "block").executes(ctx -> center(ctx, "block"))));
        dispatcher.register(Commands.literal("copy").executes(ctx -> copy(ctx)));
        dispatcher.register(Commands.literal("cut").executes(ctx -> cut(ctx)));
        dispatcher.register(Commands.literal("paste").executes(ctx -> paste(ctx)));
        dispatcher.register(Commands.literal("move")
                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> move(ctx, "me", IntegerArgumentType.getInteger(ctx, "count")))
                        .then(Commands.argument("direction", StringArgumentType.word())
                                .executes(ctx -> move(ctx, StringArgumentType.getString(ctx, "direction"),
                                        IntegerArgumentType.getInteger(ctx, "count"))))));
        dispatcher.register(Commands.literal("stack")
                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                        .executes(ctx -> stack(ctx, "me", IntegerArgumentType.getInteger(ctx, "count")))
                        .then(Commands.argument("direction", StringArgumentType.word())
                                .executes(ctx -> stack(ctx, StringArgumentType.getString(ctx, "direction"),
                                        IntegerArgumentType.getInteger(ctx, "count"))))));
        dispatcher.register(Commands.literal("expand")
                .executes(ctx -> expand(ctx, "me", 1, true))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> expand(ctx, "me", IntegerArgumentType.getInteger(ctx, "amount"), true))
                        .then(Commands.argument("direction", StringArgumentType.word())
                                .executes(ctx -> expand(ctx, StringArgumentType.getString(ctx, "direction"),
                                        IntegerArgumentType.getInteger(ctx, "amount"), true)))));
        dispatcher.register(Commands.literal("contract")
                .executes(ctx -> expand(ctx, "me", 1, false))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> expand(ctx, "me", IntegerArgumentType.getInteger(ctx, "amount"), false))
                        .then(Commands.argument("direction", StringArgumentType.word())
                                .executes(ctx -> expand(ctx, StringArgumentType.getString(ctx, "direction"),
                                        IntegerArgumentType.getInteger(ctx, "amount"), false)))));
        dispatcher.register(Commands.literal("shift")
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> shift(ctx, "me", IntegerArgumentType.getInteger(ctx, "amount")))
                        .then(Commands.argument("direction", StringArgumentType.word())
                                .executes(ctx -> shift(ctx, StringArgumentType.getString(ctx, "direction"),
                                        IntegerArgumentType.getInteger(ctx, "amount"))))));
        dispatcher.register(Commands.literal("undo").executes(ctx -> undo(ctx)));
        dispatcher.register(Commands.literal("redo").executes(ctx -> redo(ctx)));
        dispatcher.register(Commands.literal("clear").executes(ctx -> clearSelection(ctx)));
        dispatcher.register(Commands.literal("clearinventory").executes(ctx -> clearInventory(ctx)));
        dispatcher.register(Commands.literal("clearentities")
                .executes(ctx -> clearEntities(ctx, -1))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .executes(ctx -> clearEntities(ctx, IntegerArgumentType.getInteger(ctx, "radius")))));
    }

    // ------------------------------------------------------------------
    // Command helpers
    // ------------------------------------------------------------------

    private static ArgumentBuilder<CommandSourceStack, ?> blockArg(CommandBuildContext buildContext, String name) {
        return Commands.argument(name, BlockStateArgument.block(buildContext));
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static BlockState blockState(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return BlockStateArgument.getBlock(ctx, name).getState();
    }

    private static void message(ServerPlayer player, String text) {
        BuilderServerHandler.sendMessage(player, text);
    }

    private static void error(ServerPlayer player, String text) {
        BuilderServerHandler.sendError(player, text);
    }

    private static boolean validRegion(ServerPlayer player, SelectionStore.Region region) {
        if (region == null) {
            error(player, "No selection - set both corners with the Selection Tool (or /pos1 + /pos2).");
            return false;
        }
        if (region.volume() > MAX_BLOCKS) {
            error(player, "Selection is too large (max " + MAX_BLOCKS + " blocks).");
            return false;
        }
        Level level = player.level();
        if (!level.hasChunksAt(region.min(), region.max())) {
            error(player, "Selection is not fully loaded.");
            return false;
        }
        if (player.distanceToSqr(Vec3.atCenterOf(region.min())) > MAX_DISTANCE * MAX_DISTANCE
                || player.distanceToSqr(Vec3.atCenterOf(region.max())) > MAX_DISTANCE * MAX_DISTANCE) {
            error(player, "Selection is too far away (max " + (int) MAX_DISTANCE + " blocks).");
            return false;
        }
        return true;
    }

    /** Parses a WorldEdit-style direction ("north"/"n", "up"/"u", ... or "me" for facing). */
    private static Vec3i direction(ServerPlayer player, String dir) {
        return switch (dir.toLowerCase(Locale.ROOT)) {
            case "up", "u" -> new Vec3i(0, 1, 0);
            case "down", "d" -> new Vec3i(0, -1, 0);
            case "north", "n" -> new Vec3i(0, 0, -1);
            case "south", "s" -> new Vec3i(0, 0, 1);
            case "west", "w" -> new Vec3i(-1, 0, 0);
            case "east", "e" -> new Vec3i(1, 0, 0);
            default -> horizontalLook(player);
        };
    }

    private static Vec3i horizontalLook(ServerPlayer player) {
        float yaw = player.getYRot();
        int x = (int) Math.round(-Math.sin(Math.toRadians(yaw)));
        int z = (int) Math.round(-Math.cos(Math.toRadians(yaw)));
        return new Vec3i(x, 0, z);
    }

    // ------------------------------------------------------------------
    // Selection helpers (server side)
    // ------------------------------------------------------------------

    private static SelectionStore.Region region(ServerPlayer player) {
        return SelectionStore.get(player);
    }

    /** Pushes the new region back to the client so the box follows expand/contract/shift. */
    private static void applySelectionChange(ServerPlayer player, BlockPos min, BlockPos max) {
        SelectionStore.setRegion(player, min, max);
        player.connection.send(new SelectionSyncPacket(
                true, min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
    }

    private record Entry(BlockPos rel, BlockState state, CompoundTag nbt) {
    }

    private static Entry captureEntry(Level level, BlockPos min, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        CompoundTag nbt = null;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            nbt = blockEntity.saveWithFullMetadata(level.registryAccess());
        }
        return new Entry(pos.subtract(min), state, nbt);
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    private static int wand(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        give(player, new ItemStack(ModItems.SELECTION_TOOL.get()));
        message(player, "Gave you the Selection Tool.");
        return 1;
    }

    /** Gives every builder tool at once (handy after the mod id rename dropped old-save items). */
    private static int tools(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        List<ItemStack> stacks = List.of(
                new ItemStack(ModItems.SELECTION_TOOL.get()),
                new ItemStack(ModItems.ENTITY_TOOL.get()),
                new ItemStack(ModItems.RULER_TOOL.get()),
                new ItemStack(ModItems.LASER_TOOL.get()),
                new ItemStack(ModItems.SCATTER_TOOL.get()),
                new ItemStack(ModItems.SMOOTH_TOOL.get()),
                new ItemStack(ModItems.PAINT_TOOL.get()));
        for (ItemStack stack : stacks) {
            give(player, stack);
        }
        message(player, "Gave you all " + stacks.size() + " builder tools.");
        return 1;
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static int pos1(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.setCorner1(player, player.blockPosition());
        syncCurrent(player);
        return 1;
    }

    private static int pos2(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.setCorner2(player, player.blockPosition());
        syncCurrent(player);
        return 1;
    }

    private static void syncCurrent(ServerPlayer player) {
        SelectionStore.Region r = region(player);
        if (r != null) {
            applySelectionChange(player, r.min(), r.max());
            message(player, "Selection: " + r.min() + " to " + r.max() + " (" + r.volume() + " blocks).");
        } else {
            SelectionStore.clear(player);
            player.connection.send(SelectionSyncPacket.clear());
            error(player, "Selection cleared.");
        }
    }

    private static int sel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (r == null) {
            error(player, "No selection.");
            return 0;
        }
        message(player, "Selection: " + r.min() + " to " + r.max()
                + " - " + (r.max().getX() - r.min().getX() + 1) + "x"
                + (r.max().getY() - r.min().getY() + 1) + "x"
                + (r.max().getZ() - r.min().getZ() + 1)
                + " (" + r.volume() + " blocks).");
        return 1;
    }

    /** Runs a predicate over the region and sets matching blocks to {@code state}. */
    private static int applyRegion(ServerPlayer player, SelectionStore.Region region,
                                   BiPredicate<Level, BlockPos> predicate, BlockState state, CompoundTag nbt) {
        Level level = player.level();
        List<BlockChange> changes = new ArrayList<>();
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(region.min(), region.max())) {
            BlockPos p = pos.immutable();
            if (!predicate.test(level, p)) {
                continue;
            }
            changes.add(BuilderServerHandler.capture(level, p));
            BuilderServerHandler.setBlockWithEntity(level, p, state, nbt);
            count++;
        }
        if (count > 0) {
            UndoStore.push(player, changes);
        }
        return count;
    }

    private static int setBlocks(CommandContext<CommandSourceStack> ctx, String blockName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockState state = blockState(ctx, blockName);
        int count = applyRegion(player, r, (level, pos) -> true, state, null);
        message(player, "Set " + count + " block(s).");
        return 1;
    }

    /** /clear - wipes every block in the selected area (and any off-grid blocks in it). */
    private static int clearSelection(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        Level level = player.level();
        int count = applyRegion(player, r, (level2, pos) -> !level2.getBlockState(pos).isAir(),
                Blocks.AIR.defaultBlockState(), null);
        // Off-grid blocks are solid entities, not block states - remove them too so the wipe is
        // complete.
        for (OffGridBlockEntity block : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(r.min().getX(), r.min().getY(), r.min().getZ(),
                        r.max().getX() + 1, r.max().getY() + 1, r.max().getZ() + 1))) {
            if (block.getTags().contains(BuilderServerHandler.OFF_GRID_TAG)) {
                block.discardWithDisplay();
            }
        }
        // Rotated blocks of the mod's layer in the selection are wiped too.
        int freeCount = 0;
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                    r.min().getX(), r.min().getY(), r.min().getZ(),
                    r.max().getX(), r.max().getY(), r.max().getZ())) {
                if (RotationStore.hasRotation(serverLevel, pos)) {
                    RotationStore.remove(serverLevel, pos.immutable());
                    freeCount++;
                }
            }
        }
        message(player, "Cleared " + count + " block(s) from the selection"
                + (freeCount > 0 ? " + " + freeCount + " rotated block(s)" : "") + ".");
        return 1;
    }

    /** /clearinventory - the vanilla /clear behavior (empties the player's inventory). */
    private static int clearInventory(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        player.getInventory().clearContent();
        message(player, "Cleared your inventory.");
        return 1;
    }

    /**
     * /clearentities - removes every entity inside the selected area; with a radius argument,
     * every entity within that many blocks of the player instead. Players are never removed.
     * Off-grid blocks are entities here too, so they are removed with their display child.
     */
    private static int clearEntities(CommandContext<CommandSourceStack> ctx, int radius) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        Level level = player.level();
        AABB box;
        String what;
        if (radius > 0) {
            box = player.getBoundingBox().inflate(radius);
            what = "radius " + radius;
        } else {
            SelectionStore.Region r = region(player);
            if (!validRegion(player, r)) {
                return 0;
            }
            box = new AABB(r.min().getX(), r.min().getY(), r.min().getZ(),
                    r.max().getX() + 1, r.max().getY() + 1, r.max().getZ() + 1);
            what = "selection";
        }
        int count = 0;
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box)) {
            if (entity instanceof Player) {
                continue;
            }
            if (entity instanceof OffGridBlockEntity block) {
                block.discardWithDisplay();
            } else if (entity instanceof Display
                    && entity.getTags().contains(BuilderServerHandler.OFF_GRID_TAG)) {
                // The solid counterpart removes the display; only orphan it if the solid itself is
                // outside the box (e.g. solid removed earlier in this pass).
                if (BuilderServerHandler.findOffGrid(level, entity.getX(), entity.getY(), entity.getZ()) == null) {
                    entity.discard();
                }
                continue;
            } else {
                entity.discard();
            }
            count++;
        }
        message(player, "Removed " + count + " entit(ies) in the " + what + ".");
        return 1;
    }

    private static int replaceAll(CommandContext<CommandSourceStack> ctx, String toName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockState to = blockState(ctx, toName);
        int count = applyRegion(player, r, (level, pos) -> !level.getBlockState(pos).isAir(), to, null);
        message(player, "Replaced " + count + " block(s).");
        return 1;
    }

    private static int replace(CommandContext<CommandSourceStack> ctx, String fromName, String toName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockState from = blockState(ctx, fromName);
        BlockState to = blockState(ctx, toName);
        int count = applyRegion(player, r,
                (level, pos) -> level.getBlockState(pos).getBlock() == from.getBlock(), to, null);
        message(player, "Replaced " + count + " block(s).");
        return 1;
    }

    private static int walls(CommandContext<CommandSourceStack> ctx, String blockName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockPos min = r.min();
        BlockPos max = r.max();
        BlockState state = blockState(ctx, blockName);
        int count = applyRegion(player, r, (level, pos) ->
                pos.getX() == min.getX() || pos.getX() == max.getX()
                        || pos.getZ() == min.getZ() || pos.getZ() == max.getZ(), state, null);
        message(player, "Set " + count + " wall block(s).");
        return 1;
    }

    private static int outline(CommandContext<CommandSourceStack> ctx, String blockName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockPos min = r.min();
        BlockPos max = r.max();
        BlockState state = blockState(ctx, blockName);
        int count = applyRegion(player, r, (level, pos) ->
                pos.getX() == min.getX() || pos.getX() == max.getX()
                        || pos.getY() == min.getY() || pos.getY() == max.getY()
                        || pos.getZ() == min.getZ() || pos.getZ() == max.getZ(), state, null);
        message(player, "Set " + count + " outline block(s).");
        return 1;
    }

    private static int hollow(CommandContext<CommandSourceStack> ctx, String blockName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockPos min = r.min();
        BlockPos max = r.max();
        BlockState shell = blockState(ctx, blockName);
        Level level = player.level();
        List<BlockChange> changes = new ArrayList<>();
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            boolean boundary = pos.getX() == min.getX() || pos.getX() == max.getX()
                    || pos.getY() == min.getY() || pos.getY() == max.getY()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ();
            BlockState target = boundary ? shell : Blocks.AIR.defaultBlockState();
            if (level.getBlockState(pos).equals(target)) {
                continue;
            }
            changes.add(BuilderServerHandler.capture(level, pos.immutable()));
            level.setBlock(pos, target, 3);
            count++;
        }
        if (count > 0) {
            UndoStore.push(player, changes);
        }
        message(player, "Hollowed " + count + " block(s).");
        return 1;
    }

    private static int faces(CommandContext<CommandSourceStack> ctx, String blockName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockPos min = r.min();
        BlockPos max = r.max();
        BlockState state = blockState(ctx, blockName);
        int count = applyRegion(player, r, (level, pos) ->
                pos.getY() == min.getY() || pos.getY() == max.getY(), state, null);
        message(player, "Set " + count + " face block(s).");
        return 1;
    }

    private static int overlay(CommandContext<CommandSourceStack> ctx, String blockName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockPos min = r.min();
        BlockPos max = r.max();
        BlockState state = blockState(ctx, blockName);
        Level level = player.level();
        List<BlockChange> changes = new ArrayList<>();
        int count = 0;
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                int top = -1;
                for (int y = max.getY(); y >= min.getY(); y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        top = y;
                        break;
                    }
                }
                if (top >= min.getY() && top < max.getY()) {
                    BlockPos above = new BlockPos(x, top + 1, z);
                    changes.add(BuilderServerHandler.capture(level, above));
                    level.setBlock(above, state, 3);
                    count++;
                }
            }
        }
        if (count > 0) {
            UndoStore.push(player, changes);
        }
        message(player, "Overlaid " + count + " block(s).");
        return 1;
    }

    private static int center(CommandContext<CommandSourceStack> ctx, String blockName) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BlockPos min = r.min();
        BlockPos max = r.max();
        BlockState state = blockState(ctx, blockName);
        // The centre spans one block on odd axes and two on even ones.
        int cx1 = (min.getX() + max.getX()) / 2;
        int cx2 = cx1 + (((max.getX() - min.getX()) % 2 == 0) ? 0 : 1);
        int cy1 = (min.getY() + max.getY()) / 2;
        int cy2 = cy1 + (((max.getY() - min.getY()) % 2 == 0) ? 0 : 1);
        int cz1 = (min.getZ() + max.getZ()) / 2;
        int cz2 = cz1 + (((max.getZ() - min.getZ()) % 2 == 0) ? 0 : 1);
        BlockPos cMin = new BlockPos(cx1, cy1, cz1);
        BlockPos cMax = new BlockPos(cx2, cy2, cz2);
        int count = 0;
        List<BlockChange> changes = new ArrayList<>();
        Level level = player.level();
        for (BlockPos pos : BlockPos.betweenClosed(cMin, cMax)) {
            changes.add(BuilderServerHandler.capture(level, pos.immutable()));
            level.setBlock(pos, state, 3);
            count++;
        }
        if (count > 0) {
            UndoStore.push(player, changes);
        }
        message(player, "Set " + count + " centre block(s).");
        return 1;
    }

    private static int copy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BuilderServerHandler.copySelection(player, r.min(), r.max());
        return 1;
    }

    private static int cut(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        BuilderServerHandler.copySelection(player, r.min(), r.max());
        int count = applyRegion(player, r, (level, pos) -> true, Blocks.AIR.defaultBlockState(), null);
        message(player, "Cut " + count + " block(s) to the clipboard.");
        return 1;
    }

    private static int paste(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        BuilderServerHandler.paste(player, player.blockPosition());
        return 1;
    }

    private static int move(CommandContext<CommandSourceStack> ctx, String dirName, int count) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        Vec3i dir = direction(player, dirName);
        Level level = player.level();
        BlockPos min = r.min();

        // Snapshot the source, then clear it and place everything at the offset target.
        List<Entry> entries = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(r.min(), r.max())) {
            if (!level.getBlockState(pos).isAir()) {
                entries.add(captureEntry(level, min, pos));
            }
        }
        List<BlockChange> changes = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(r.min(), r.max())) {
            changes.add(BuilderServerHandler.capture(level, pos.immutable()));
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        int placed = 0;
        for (Entry entry : entries) {
            BlockPos target = min.offset(entry.rel().getX() + dir.getX() * count,
                    entry.rel().getY() + dir.getY() * count,
                    entry.rel().getZ() + dir.getZ() * count);
            if (!level.hasChunkAt(target)) {
                continue;
            }
            changes.add(BuilderServerHandler.capture(level, target));
            BuilderServerHandler.setBlockWithEntity(level, target, entry.state(), entry.nbt());
            placed++;
        }
        UndoStore.push(player, changes);
        message(player, "Moved " + placed + " block(s).");
        return 1;
    }

    private static int stack(CommandContext<CommandSourceStack> ctx, String dirName, int count) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (!validRegion(player, r)) {
            return 0;
        }
        Vec3i dir = direction(player, dirName);
        Level level = player.level();
        int span = switch (dir) {
            case Vec3i v when v.getX() != 0 -> r.max().getX() - r.min().getX() + 1;
            case Vec3i v when v.getY() != 0 -> r.max().getY() - r.min().getY() + 1;
            default -> r.max().getZ() - r.min().getZ() + 1;
        };
        List<BlockChange> changes = new ArrayList<>();
        int placed = 0;
        for (int i = 1; i <= count; i++) {
            int offset = span * i;
            for (BlockPos pos : BlockPos.betweenClosed(r.min(), r.max())) {
                BlockPos target = pos.offset(dir.getX() * offset, dir.getY() * offset, dir.getZ() * offset);
                if (!level.hasChunkAt(target)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                BlockEntity blockEntity = level.getBlockEntity(pos);
                CompoundTag nbt = blockEntity != null
                        ? blockEntity.saveWithFullMetadata(level.registryAccess()) : null;
                changes.add(BuilderServerHandler.capture(level, target));
                BuilderServerHandler.setBlockWithEntity(level, target, state, nbt);
                placed++;
            }
        }
        UndoStore.push(player, changes);
        message(player, "Stacked " + placed + " block(s).");
        return 1;
    }

    private static int expand(CommandContext<CommandSourceStack> ctx, String dirName, int amount, boolean out) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (r == null) {
            error(player, "No selection.");
            return 0;
        }
        Vec3i dir = direction(player, dirName);
        int sign = out ? 1 : -1;
        BlockPos min = r.min();
        BlockPos max = r.max();
        int dx = dir.getX() * sign * amount;
        int dy = dir.getY() * sign * amount;
        int dz = dir.getZ() * sign * amount;
        // Move the face the direction points at (or both faces for "me" horizontal look).
        if (dx < 0) {
            min = min.offset(dx, 0, 0);
        } else if (dx > 0) {
            max = max.offset(dx, 0, 0);
        }
        if (dy < 0) {
            min = min.offset(0, dy, 0);
        } else if (dy > 0) {
            max = max.offset(0, dy, 0);
        }
        if (dz < 0) {
            min = min.offset(0, 0, dz);
        } else if (dz > 0) {
            max = max.offset(0, 0, dz);
        }
        // Keep the region valid (contract past the opposite face collapses to the centre).
        BlockPos nMin = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        BlockPos nMax = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        if (nMin.getX() > nMax.getX() || nMin.getY() > nMax.getY() || nMin.getZ() > nMax.getZ()) {
            int cx = (r.min().getX() + r.max().getX()) / 2;
            int cy = (r.min().getY() + r.max().getY()) / 2;
            int cz = (r.min().getZ() + r.max().getZ()) / 2;
            nMin = new BlockPos(cx, cy, cz);
            nMax = nMin;
        }
        applySelectionChange(player, nMin, nMax);
        message(player, "Selection " + (out ? "expanded" : "contracted") + " by " + amount + " block(s).");
        return 1;
    }

    private static int shift(CommandContext<CommandSourceStack> ctx, String dirName, int amount) throws CommandSyntaxException {
        ServerPlayer player = player(ctx);
        SelectionStore.Region r = region(player);
        if (r == null) {
            error(player, "No selection.");
            return 0;
        }
        Vec3i dir = direction(player, dirName);
        BlockPos min = r.min().offset(dir.getX() * amount, dir.getY() * amount, dir.getZ() * amount);
        BlockPos max = r.max().offset(dir.getX() * amount, dir.getY() * amount, dir.getZ() * amount);
        applySelectionChange(player, min, max);
        message(player, "Shifted selection by " + amount + " block(s).");
        return 1;
    }

    private static int undo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UndoStore.undo(player(ctx));
        return 1;
    }

    private static int redo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UndoStore.redo(player(ctx));
        return 1;
    }
}
