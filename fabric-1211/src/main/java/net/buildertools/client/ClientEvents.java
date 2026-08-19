package net.buildertools.client;

import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.buildertools.network.packet.EntityDeletePacket;
import net.buildertools.network.packet.EntityDuplicatePacket;
import net.buildertools.network.packet.EntityFreezePacket;
import net.buildertools.network.packet.EntityTransformPacket;
import net.buildertools.network.packet.OffGridBlockPacket;
import net.buildertools.network.packet.PaintPacket;
import net.buildertools.network.packet.PastePacket;
import net.buildertools.network.packet.ScatterPacket;
import net.buildertools.network.packet.SelectionCopyPacket;
import net.buildertools.network.packet.SelectionFillPacket;
import net.buildertools.network.packet.SelectionSyncPacket;
import net.buildertools.network.packet.SmoothPacket;
import net.buildertools.network.packet.StretchPacket;
import net.buildertools.network.packet.UndoPacket;
import net.buildertools.client.settings.BuilderSettings;
import net.buildertools.registry.ModSounds;
import net.buildertools.selection.RulerState;
import net.buildertools.selection.SelectionHandles;
import net.buildertools.selection.SelectionManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Client-side tool interactions. Block/entity interaction callbacks handle the standard click
 * paths; the raw mouse press/release/scroll hooks come from the MouseHandlerMixin (Fabric API
 * has no raw mouse events), which lets handle-grabbing work from any distance and on air.
 */
public final class ClientEvents {
    private ClientEvents() {
    }

    public static void register() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            Item item = player.getMainHandItem().getItem();
            if (item instanceof SelectionToolItem) {
                if (level.isClientSide() && !HandleDragState.isDragging()) {
                    SelectionManager.setCorner1(pos);
                    player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                }
                return InteractionResult.FAIL;
            } else if (item instanceof RulerToolItem) {
                if (level.isClientSide()) {
                    RulerState.setPointA(pos);
                    player.playSound(ModSounds.RULER_POINT_A, 1.0f, 1.0f);
                    showRulerDistance(player);
                }
                return InteractionResult.FAIL;
            } else if (isBrush(item)) {
                if (level.isClientSide()) {
                    applyBrush(player, pos, item);
                }
                return InteractionResult.FAIL;
            } else if (item instanceof LaserToolItem) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Item item = player.getMainHandItem().getItem();
            BlockPos pos = hitResult.getBlockPos();
            if (item instanceof SelectionToolItem) {
                if (level.isClientSide()) {
                    if (player.isShiftKeyDown()) {
                        SelectionManager.clearSelection();
                        player.playSound(ModSounds.CLEAR_SELECTION, 1.0f, 1.0f);
                    } else {
                        SelectionManager.setCorner2(pos);
                        player.playSound(ModSounds.SET_CORNER_2, 1.0f, 1.0f);
                    }
                }
                return InteractionResult.FAIL;
            } else if (item instanceof EntityToolItem) {
                if (level.isClientSide()) {
                    if (EntityRotateState.isActive()) {
                        // Right-click confirms and leaves rotate mode (Hytale "cancel movement").
                        EntityRotateState.stop();
                        player.playSound(ModSounds.ENTITY_ROTATE, 1.0f, 1.0f);
                    } else if (player.isShiftKeyDown()) {
                        SelectionManager.clearSelectedEntity();
                    } else {
                        Entity entity = SelectionManager.getSelectedEntity();
                        if (entity != null && !entity.isRemoved()) {
                            // Move the selected entity on top of the block that was clicked.
                            ClientPackets.sendToServer(new EntityTransformPacket(
                                    entity.getId(),
                                    pos.getX() + 0.5,
                                    pos.getY() + 1.0,
                                    pos.getZ() + 0.5,
                                    entity.getYRot(),
                                    entity.getXRot(),
                                    false));
                        }
                    }
                }
                return InteractionResult.FAIL;
            } else if (item instanceof RulerToolItem) {
                if (level.isClientSide()) {
                    if (player.isShiftKeyDown()) {
                        RulerState.clear();
                        player.playSound(ModSounds.RULER_CLEAR, 1.0f, 1.0f);
                    } else {
                        RulerState.setPointB(pos);
                        player.playSound(ModSounds.RULER_POINT_B, 1.0f, 1.0f);
                        showRulerDistance(player);
                    }
                }
                return InteractionResult.FAIL;
            } else if (isBrush(item)) {
                if (level.isClientSide()) {
                    applyBrush(player, pos, item);
                }
                return InteractionResult.FAIL;
            } else if (item instanceof LaserToolItem) {
                return InteractionResult.FAIL;
            } else if (item instanceof BlockItem) {
                // Off-grid placement. With R pressed the block is placed at the preview cell with the
                // adjusted rotation; otherwise, if the new block's cell touches an off-grid block, the
                // rotation is inherited so a rotated formation can be built block by block.
                if (level.isClientSide()) {
                    if (BlockRotateState.isActive()) {
                        BlockPos cell = BlockRotateState.getTarget();
                        if (cell != null) {
                            ClientPackets.sendToServer(new OffGridBlockPacket(
                                    cell.getX(), cell.getY(), cell.getZ(),
                                    BlockRotateState.getYawDeg(), BlockRotateState.getPitchDeg(), false));
                        }
                        BlockRotateState.stop();
                        player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                    } else {
                        BlockPos cell = hitResult.getBlockPos().relative(hitResult.getDirection());
                        float[] inherited = findInheritedRotation(player, cell);
                        if (inherited != null) {
                            ClientPackets.sendToServer(new OffGridBlockPacket(
                                    cell.getX(), cell.getY(), cell.getZ(), inherited[0], inherited[1], false));
                            player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                            return InteractionResult.FAIL;
                        }
                    }
                }
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, level, hand, target, hitResult) -> {
            Item item = player.getMainHandItem().getItem();
            if (item instanceof EntityToolItem) {
                if (level.isClientSide()) {
                    Entity current = SelectionManager.getSelectedEntity();
                    if (current == target) {
                        SelectionManager.clearSelectedEntity();
                        player.playSound(ModSounds.ENTITY_DESELECT, 1.0f, 1.0f);
                    } else {
                        SelectionManager.setSelectedEntity(target);
                        player.playSound(ModSounds.ENTITY_SELECT, 1.0f, 1.0f);
                    }
                }
                return InteractionResult.FAIL;
            } else if (isBuilderTool(item)) {
                // Don't let right-clicking an entity (e.g. a villager) open its GUI while a tool is held.
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, level, hand, target, hitResult) -> {
            if (isBuilderTool(player.getMainHandItem().getItem())) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        ClientTickEvents.START_CLIENT_TICK.register(minecraft -> {
            if (minecraft.player == null || minecraft.screen != null) {
                return;
            }
            // While the off-grid placement preview is up, Enter confirms the placement instead of
            // opening chat. Swallowing the chat key here (before the game's own consumeClick runs)
            // keeps Enter from opening the chat screen.
            if (BlockRotateState.isActive()) {
                while (minecraft.options.keyChat.consumeClick()) {
                }
            }
            if (minecraft.gameMode == null || !minecraft.gameMode.hasInfiniteItems()) {
                return;
            }
            if (minecraft.options.keyInventory.consumeClick()) {
                minecraft.setScreen(new CreativeSettingsScreen((net.minecraft.client.player.LocalPlayer) minecraft.player));
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(ClientEvents::onEndTick);

        HudRenderCallback.EVENT.register((graphics, deltaTracker) -> renderLegend(graphics));
    }

    private static boolean isBuilderTool(Item item) {
        return item instanceof SelectionToolItem
                || item instanceof EntityToolItem
                || item instanceof RulerToolItem
                || item instanceof LaserToolItem
                || item instanceof ScatterToolItem
                || item instanceof SmoothToolItem
                || item instanceof PaintToolItem;
    }

    private static boolean isBrush(Item item) {
        return item instanceof PaintToolItem || item instanceof ScatterToolItem || item instanceof SmoothToolItem;
    }

    private static void applyBrush(Player player, BlockPos pos, Item item) {
        if (item instanceof PaintToolItem) {
            ClientPackets.sendToServer(new PaintPacket(pos));
        } else if (item instanceof ScatterToolItem) {
            ClientPackets.sendToServer(new ScatterPacket(pos));
        } else if (item instanceof SmoothToolItem) {
            ClientPackets.sendToServer(new SmoothPacket(pos));
        }
    }

    private static void showRulerDistance(Player player) {
        if (!RulerState.hasMeasurement()) {
            return;
        }
        Vec3 a = Vec3.atCenterOf(RulerState.getPointA());
        Vec3 b = Vec3.atCenterOf(RulerState.getPointB());
        BlockPos pa = RulerState.getPointA();
        BlockPos pb = RulerState.getPointB();
        player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "Ruler: %.1f blocks  (X %d, Y %d, Z %d)",
                a.distanceTo(b),
                Math.abs(pb.getX() - pa.getX()),
                Math.abs(pb.getY() - pa.getY()),
                Math.abs(pb.getZ() - pa.getZ()))), true);
    }

    // ------------------------------------------------------------------
    // Raw mouse hooks (called from MouseHandlerMixin)
    // ------------------------------------------------------------------

    /**
     * Raw mouse button callback from the mixin. Returns true if the press was consumed (the
     * vanilla click handling is then skipped for this button). Handles handle-grabbing (selection
     * tool), brush clicks with reach/air placement, entity select/grab and ruler points.
     */
    public static boolean onMousePress(int button, int action, int mods) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return false;
        }
        Player player = minecraft.player;
        ItemStack held = player.getMainHandItem();
        Item item = held.getItem();

        boolean shift = (mods & 0x1) != 0; // GLFW_MOD_SHIFT
        boolean alt = (mods & 0x2) != 0;   // GLFW_MOD_ALT
        boolean left = button == 0;        // GLFW_MOUSE_BUTTON_LEFT
        boolean right = button == 1;       // GLFW_MOUSE_BUTTON_RIGHT

        if (action == 1) { // GLFW_RELEASE
            return onMouseRelease(button);
        }

        // Press handling.
        if (item instanceof SelectionToolItem && left) {
            // Grab a handle the cursor is on, from up to 64 blocks away. Alt turns the drag into
            // a stretch: on release the blocks inside are scaled along the dragged axis.
            SelectionHandles.Hit handleHit = raycastHandle(player, 64.0);
            if (handleHit != null) {
                Vec3 grab = player.getEyePosition(1.0f).add(player.getLookAngle().scale(handleHit.t()));
                HandleDragState.start(handleHit.handle().axis(), handleHit.handle().positive(),
                        grab, player.getLookAngle(), alt);
                player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                return true;
            }
            // No handle: fall through to the normal corner click (handled by AttackBlockCallback),
            // so we only consume the press when a handle was actually grabbed.
            return false;
        } else if (isBrush(item) && (left || right)) {
            // Brush clicks work at the configured reach, and with Air Placement on they also work
            // when clicking empty air (painting at the air-place distance).
            HitResult hit = player.pick(BuilderSettings.getToolReach(), 1.0f, false);
            BlockPos target = null;
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                target = ((BlockHitResult) hit).getBlockPos();
            } else if (BuilderSettings.isAirPlacement()) {
                Vec3 pos = player.getEyePosition(1.0f)
                        .add(player.getLookAngle().scale(BuilderSettings.getAirPlaceDistance()));
                target = BlockPos.containing(pos);
            }
            if (target != null) {
                applyBrush(player, target, item);
                return true;
            }
            return false;
        } else if (item instanceof EntityToolItem && right) {
            HitResult hit = minecraft.hitResult;
            if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                Entity target = ((EntityHitResult) hit).getEntity();
                if (target != null) {
                    Entity current = SelectionManager.getSelectedEntity();
                    if (EntityRotateState.isActive()) {
                        // Right-click confirms and leaves rotate mode.
                        EntityRotateState.stop();
                        player.playSound(ModSounds.ENTITY_ROTATE, 1.0f, 1.0f);
                    } else if (shift) {
                        // Shift + right-click: deselect.
                        SelectionManager.clearSelectedEntity();
                        player.playSound(ModSounds.ENTITY_DESELECT, 1.0f, 1.0f);
                    } else if (current == target) {
                        // Right-click the selected entity: grab it and free-move it with the mouse.
                        EntityRotateState.stop();
                        EntityDragState.start(target);
                        player.playSound(ModSounds.ENTITY_SELECT, 1.0f, 1.0f);
                    } else {
                        SelectionManager.setSelectedEntity(target);
                        player.playSound(ModSounds.ENTITY_SELECT, 1.0f, 1.0f);
                    }
                    return true;
                }
            }
            return false;
        } else if (item instanceof BlockItem && left) {
            if (BlockRotateState.isActive()) {
                // Left click is reserved for hold-to-rotate; suppress the vanilla attack.
                return true;
            }
            // Break an off-grid block: raycast the display entities and remove the one hit.
            BlockPos cell = raycastOffGrid(player, 6.0);
            if (cell != null) {
                ClientPackets.sendToServer(new OffGridBlockPacket(
                        cell.getX(), cell.getY(), cell.getZ(), 0.0f, 0.0f, true));
                player.playSound(ModSounds.SET_CORNER_2, 1.0f, 1.0f);
                return true;
            }
            return false;
        } else if (item instanceof BlockItem && right) {
            // Right-clicking a placed off-grid block places the next block beside it (on the
            // clicked face) with the same rotation, so a rotated formation can be built by
            // clicking block after block - no R needed for every piece.
            if (!BlockRotateState.isActive()) {
                OffGridHit hit = raycastOffGridHit(player, 6.0);
                if (hit != null) {
                    BlockPos cell = hit.block.cell().relative(hit.face);
                    ClientPackets.sendToServer(new OffGridBlockPacket(
                            cell.getX(), cell.getY(), cell.getZ(),
                            hit.block.getPlacementYaw(), hit.block.getPlacementPitch(), false));
                    player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    /** Whether the left or right Alt key is currently held. */
    private static boolean isAltDown(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    private static boolean onMouseRelease(int button) {
        if (button == 1 && EntityDragState.isDragging()) {
            EntityDragState.stop();
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                player.playSound(ModSounds.ENTITY_MOVE, 1.0f, 1.0f);
            }
            return true;
        }
        if (!HandleDragState.isDragging() || button != 0) {
            return false;
        }
        // Alt+drag: commit the stretch (the server remaps the blocks and plays the sound).
        boolean stretch = HandleDragState.isStretch();
        boolean moved = HandleDragState.hasMoved();
        Direction.Axis axis = HandleDragState.axis();
        boolean positive = HandleDragState.positive();
        BlockPos origMin = HandleDragState.origMin();
        BlockPos origMax = HandleDragState.origMax();
        HandleDragState.stop(true);
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return true;
        }
        if (stretch) {
            if (moved && origMin != null && origMax != null && SelectionManager.hasSelection()) {
                ClientPackets.sendToServer(StretchPacket.create(axis.ordinal(), positive,
                        origMin, origMax, SelectionManager.getMin(), SelectionManager.getMax()));
            }
            return true;
        }
        player.playSound(ModSounds.SET_CORNER_2, 1.0f, 1.0f);
        return true;
    }

    /**
     * Raw scroll callback from the mixin. Returns true if the scroll was consumed.
     */
    public static boolean onMouseScroll(double deltaY) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return false;
        }
        Item held = player.getMainHandItem().getItem();
        if (isBrush(held) && BuilderSettings.isAirPlacement()) {
            // "Use the mouse scroll wheel to increase/decrease place distance."
            double delta = Math.signum(deltaY);
            float next = Math.round((BuilderSettings.getAirPlaceDistance() + (float) delta) * 2) / 2f;
            BuilderSettings.setAirPlaceDistance(Math.max(3.0f, Math.min(32.0f, next)));
            player.displayClientMessage(Component.literal(
                    "Air placement distance: " + BuilderSettings.getAirPlaceDistance()), true);
            return true;
        }
        if (!(held instanceof EntityToolItem)) {
            return false;
        }
        Entity entity = SelectionManager.getSelectedEntity();
        if (entity == null || entity.isRemoved()) {
            return false;
        }

        double delta = Math.signum(deltaY);
        if (player.isShiftKeyDown()) {
            // Move one block up/down.
            ClientPackets.sendToServer(new EntityTransformPacket(
                    entity.getId(),
                    entity.getX(),
                    entity.getY() + delta,
                    entity.getZ(),
                    entity.getYRot(),
                    entity.getXRot(),
                    false));
            player.playSound(ModSounds.ENTITY_MOVE, 1.0f, 1.0f);
        } else {
            // Rotate in 22.5-degree increments.
            float yaw = Math.round((entity.getYRot() + (float) delta * 22.5f) / 22.5f) * 22.5f;
            ClientPackets.sendToServer(new EntityTransformPacket(
                    entity.getId(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    yaw,
                    entity.getXRot(),
                    false));
            player.playSound(ModSounds.ENTITY_ROTATE, 1.0f, 1.0f);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Per-tick logic
    // ------------------------------------------------------------------

    private static void onEndTick(Minecraft minecraft) {
        Player player = minecraft.player;
        if (player == null) {
            return;
        }

        // Keep the server's selection store in sync so the /builder commands act on the region
        // the client is showing (corner clicks and handle drags both mark the selection dirty).
        if (SelectionManager.consumeDirty()) {
            if (SelectionManager.hasSelection()) {
                ClientPackets.sendToServer(SelectionSyncPacket.fromClient(
                        SelectionManager.getMin(), SelectionManager.getMax()));
            } else {
                ClientPackets.sendToServer(SelectionSyncPacket.clear());
            }
        }

        // Safety net for No Clip (PlayerMixin does the real work each tick).
        if (BuilderSettings.isNoClip()) {
            player.noPhysics = true;
        }

        if (minecraft.screen != null) {
            HandleDragState.stop(false);

            EntityRotateState.stop();
            BlockRotateState.stop();
            return;
        }
        ItemStack held = player.getMainHandItem();

        // While a handle is being dragged, track the mouse ray against the drag plane.
        if (HandleDragState.isDragging()) {
            if (!(held.getItem() instanceof SelectionToolItem)) {
                HandleDragState.stop(false);
            } else {
                HandleDragState.update(player.getEyePosition(1.0f), player.getLookAngle());
            }
        }

        // Free-move: while the selected entity is grabbed, it follows the cursor.
        if (EntityDragState.isDragging()) {
            if (!(held.getItem() instanceof EntityToolItem)) {
                EntityDragState.stop();
            } else {
                EntityDragState.update(player);
            }
        }

        // Rotate mode: while active, the selected entity's yaw follows the cursor around it.
        if (EntityRotateState.isActive()) {
            if (!(held.getItem() instanceof EntityToolItem) || !SelectionManager.hasSelectedEntity()) {
                EntityRotateState.stop();
            } else {
                EntityRotateState.update(player);
            }
        }

        // Off-grid placement preview: while active, the block about to be placed follows the
        // cursor around its cell. Drops out when the held block changes or a screen opens.
        if (BlockRotateState.isActive()) {
            if (!(held.getItem() instanceof BlockItem)) {
                BlockRotateState.stop();
            } else {
                BlockRotateState.update(player);
            }
        }

        if (held.getItem() instanceof SelectionToolItem) {
            while (KeyBindings.COPY.consumeClick()) {
                if (SelectionManager.hasSelection()) {
                    ClientPackets.sendToServer(new SelectionCopyPacket(SelectionManager.getMin(), SelectionManager.getMax()));
                }
            }
            while (KeyBindings.PASTE.consumeClick()) {
                BlockPos anchor = getPasteAnchor(minecraft);
                if (anchor != null) {
                    ClientPackets.sendToServer(new PastePacket(anchor));
                }
            }
            while (KeyBindings.FILL.consumeClick()) {
                if (SelectionManager.hasSelection()) {
                    ClientPackets.sendToServer(new SelectionFillPacket(SelectionManager.getMin(), SelectionManager.getMax()));
                }
            }
            while (KeyBindings.UNDO.consumeClick()) {
                ClientPackets.sendToServer(new UndoPacket());
            }
        } else if (held.getItem() instanceof EntityToolItem) {
            while (KeyBindings.ROTATE.consumeClick()) {
                if (EntityRotateState.isActive()) {
                    // R again: confirm the rotation and leave rotate mode.
                    EntityRotateState.stop();
                    player.playSound(ModSounds.ENTITY_ROTATE, 1.0f, 1.0f);
                } else {
                    Entity entity = SelectionManager.getSelectedEntity();
                    if (entity != null && !entity.isRemoved()) {
                        // R: enter rotate mode (Alt+R rotates only the head).
                        EntityDragState.stop();
                        EntityRotateState.start(entity, isAltDown(minecraft));
                        player.playSound(ModSounds.ENTITY_ROTATE, 1.0f, 1.0f);
                    }
                }
            }
            while (KeyBindings.DELETE.consumeClick()) {
                Entity entity = SelectionManager.getSelectedEntity();
                if (entity != null && !entity.isRemoved()) {
                    ClientPackets.sendToServer(new EntityDeletePacket(entity.getId()));
                    SelectionManager.clearSelectedEntity();
                }
            }
            while (KeyBindings.DUPLICATE.consumeClick()) {
                Entity entity = SelectionManager.getSelectedEntity();
                if (entity != null && !entity.isRemoved()) {
                    ClientPackets.sendToServer(new EntityDuplicatePacket(entity.getId()));
                }
            }
            while (KeyBindings.FREEZE.consumeClick()) {
                Entity entity = SelectionManager.getSelectedEntity();
                if (entity != null && !entity.isRemoved()) {
                    boolean freeze = !SelectionManager.isEntityFrozen();
                    ClientPackets.sendToServer(new EntityFreezePacket(entity.getId(), freeze));
                    SelectionManager.setEntityFrozen(freeze);
                }
            }
        } else if (held.getItem() instanceof BlockItem) {
            while (KeyBindings.ROTATE.consumeClick()) {
                if (BlockRotateState.isActive()) {
                    // R again: cancel the placement preview.
                    BlockRotateState.stop();
                    player.playSound(ModSounds.SET_CORNER_2, 1.0f, 1.0f);
                } else {
                    // R: if aiming at a placed off-grid block, re-enter its rotation editor so it
                    // can be spun in place; otherwise start the placement preview.
                    BlockPos placedCell = raycastOffGrid(player, 6.0);
                    OffGridBlockEntity placed = placedCell != null
                            ? findOffGridEntity(player.level(), placedCell) : null;
                    if (placed != null) {
                        BlockRotateState.start(player, placedCell,
                                placed.getPlacementYaw(), placed.getPlacementPitch(),
                                placed.getRepresentedState());
                    } else {
                        BlockRotateState.start(player);
                    }
                    player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                }
            }
            while (KeyBindings.CONFIRM.consumeClick()) {
                // Enter: place the block at the preview cell with the current rotation.
                BlockPos cell = BlockRotateState.getTarget();
                if (cell != null) {
                    ClientPackets.sendToServer(new OffGridBlockPacket(
                            cell.getX(), cell.getY(), cell.getZ(),
                            BlockRotateState.getYawDeg(), BlockRotateState.getPitchDeg(), false));
                    player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                }
                BlockRotateState.stop();
            }
        }
    }

    /** Renders the control-hints legend in the corner while a builder tool is held. */
    private static void renderLegend(GuiGraphics graphics) {
        if (!BuilderSettings.isDisplayLegend()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }
        String[] lines = legendFor(player.getMainHandItem().getItem());
        if (BlockRotateState.isActive()) {
            lines = new String[]{
                    "Off-grid: hold LMB + move mouse to rotate · RMB or Enter places · R cancels"};
        }
        if (lines == null) {
            return;
        }
        int x = 4;
        int y = 4;
        graphics.fill(x - 2, y - 2, x + 240, y + lines.length * 10 + 2, 0x88000000);
        for (String line : lines) {
            graphics.drawString(minecraft.font, line, x, y, 0xFFE8E8E8);
            y += 10;
        }
    }

    private static String[] legendFor(Item item) {
        if (item instanceof SelectionToolItem) {
            return new String[]{
                    "LMB corner 1 · RMB corner 2 · sneak+RMB clear",
                    "Y copy · V paste · B fill · U undo",
                    "Drag plates to resize · Alt+drag to stretch"};
        }
        if (item instanceof EntityToolItem) {
            return new String[]{
                    "RMB select · RMB-hold drag · R rotate · Alt+R head",
                    "scroll rotate · sneak+scroll up/down · X delete · J dup · G freeze"};
        }
        if (item instanceof RulerToolItem) {
            return new String[]{"LMB point A · RMB point B · sneak+RMB clear"};
        }
        if (item instanceof LaserToolItem) {
            return new String[]{"Point at blocks to measure"};
        }
        if (isBrush(item)) {
            return new String[]{
                    "LMB/RMB brush · U undo",
                    "Air Placement: scroll changes paint distance"};
        }
        return null;
    }

    /**
     * Raycasts against the selection's six face handles, preferring handles closer than the block
     * under the crosshair so a handle never hides a normal corner click. {@code maxDist} is the
     * grab range (64 blocks), so handles can be grabbed from far away.
     */
    private static SelectionHandles.Hit raycastHandle(Player player, double maxDist) {
        if (!SelectionManager.hasSelection()) {
            return null;
        }
        Vec3 origin = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        double maxT = maxDist;
        HitResult blockHit = player.pick(maxDist, 1.0f, false);
        if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
            maxT = origin.distanceTo(blockHit.getLocation());
        }
        return SelectionHandles.raycast(
                SelectionHandles.handles(SelectionManager.getMin(), SelectionManager.getMax()),
                origin, dir, maxT);
    }

    private static BlockPos getPasteAnchor(Minecraft minecraft) {
        HitResult hit = minecraft.hitResult;
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            return ((BlockHitResult) hit).getBlockPos().immutable();
        }
        Player player = minecraft.player;
        if (player != null) {
            return player.blockPosition();
        }
        return null;
    }

    /**
     * Finds the rotation inherited from an off-grid block adjacent to (or at) the given cell.
     * Returns NaN when there is no off-grid neighbor, so normal grid placement proceeds.
     */
    private static float[] findInheritedRotation(Player player, BlockPos cell) {
        List<BlockPos> cells = List.of(
                cell, cell.above(), cell.below(), cell.north(), cell.south(), cell.east(), cell.west());
        for (BlockPos neighbor : cells) {
            OffGridBlockEntity block = findOffGridEntity(player.level(), neighbor);
            if (block != null) {
                return new float[]{block.getPlacementYaw(), block.getPlacementPitch()};
            }
        }
        return null;
    }

    /** Returns the solid off-grid block occupying the given cell, or null (client-side query). */
    private static OffGridBlockEntity findOffGridEntity(net.minecraft.world.level.Level level, BlockPos pos) {
        for (OffGridBlockEntity block : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1))) {
            if (block.getTags().contains(net.buildertools.server.BuilderServerHandler.OFF_GRID_TAG)) {
                return block;
            }
        }
        return null;
    }

    /**
     * Raycasts the off-grid display entities along the look direction and returns the cell of the
     * nearest hit (or null). Used to break an off-grid block by looking at it and left-clicking.
     */
    private static BlockPos raycastOffGrid(Player player, double reach) {
        OffGridHit hit = raycastOffGridHit(player, reach);
        return hit != null ? hit.block.cell() : null;
    }

    /** The solid off-grid block under the cursor and the face of its cell that was clicked. */
    private record OffGridHit(OffGridBlockEntity block, Direction face) {
    }

    private static OffGridHit raycastOffGridHit(Player player, double reach) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        Vec3 end = eye.add(dir.scale(reach));
        double best = Double.MAX_VALUE;
        OffGridHit bestHit = null;
        for (OffGridBlockEntity block : minecraft.level.getEntitiesOfClass(OffGridBlockEntity.class,
                player.getBoundingBox().expandTowards(dir.scale(reach)).inflate(1.5))) {
            if (!block.getTags().contains(net.buildertools.server.BuilderServerHandler.OFF_GRID_TAG)) {
                continue;
            }
            Optional<Vec3> hit = block.getBoundingBox().clip(eye, end);
            if (hit.isPresent()) {
                double dist = eye.distanceToSqr(hit.get());
                if (dist < best) {
                    best = dist;
                    bestHit = new OffGridHit(block, hitFace(block.getBoundingBox(), hit.get()));
                }
            }
        }
        return bestHit;
    }

    /**
     * The face of the AABB the hit point lies on: the axis with the largest offset from the box
     * center is the surface normal. Works for any AABB (full blocks, slabs, ...).
     */
    private static Direction hitFace(AABB box, Vec3 hit) {
        double dx = hit.x - box.getCenter().x;
        double dy = hit.y - box.getCenter().y;
        double dz = hit.z - box.getCenter().z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= az) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
