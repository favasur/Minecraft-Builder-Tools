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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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

import java.util.List;
import java.util.Optional;

import java.util.Locale;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {
    }

    // ------------------------------------------------------------------
    // Tool interactions
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        Item item = player.getMainHandItem().getItem();
        if (item instanceof SelectionToolItem) {
            // Handle-grabbing is done in onMouseButtonPre (so it works from any distance and even
            // when clicking air); any other left click here just sets corner 1.
            event.setCanceled(true);
            if (event.getLevel().isClientSide() && !HandleDragState.isDragging()) {
                SelectionManager.setCorner1(event.getPos());
                player.playSound(ModSounds.SET_CORNER_1.get(), 1.0f, 1.0f);
            }
        } else if (item instanceof RulerToolItem) {
            event.setCanceled(true);
            if (event.getLevel().isClientSide()) {
                RulerState.setPointA(event.getPos());
                player.playSound(ModSounds.RULER_POINT_A.get(), 1.0f, 1.0f);
                showRulerDistance(player);
            }
        } else if (isBrush(item)) {
            event.setCanceled(true);
            if (event.getLevel().isClientSide()) {
                applyBrush(player, event.getPos(), item);
            }
        } else if (item instanceof LaserToolItem) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Item item = player.getMainHandItem().getItem();
        if (item instanceof SelectionToolItem) {
            event.setCanceled(true);
            if (event.getLevel().isClientSide()) {
                if (player.isShiftKeyDown()) {
                    SelectionManager.clearSelection();
                    player.playSound(ModSounds.CLEAR_SELECTION.get(), 1.0f, 1.0f);
                } else {
                    SelectionManager.setCorner2(event.getPos());
                    player.playSound(ModSounds.SET_CORNER_2.get(), 1.0f, 1.0f);
                }
            }
        } else if (item instanceof EntityToolItem) {
            // Right-clicking a block never moves the selected entity anywhere; entity selection
            // and free-move dragging happen on the entity hit itself (see onMouseButtonPre).
            event.setCanceled(true);
            if (event.getLevel().isClientSide()) {
                if (EntityRotateState.isActive()) {
                    // Right-click confirms and leaves rotate mode (Hytale "cancel movement").
                    EntityRotateState.stop();
                    player.playSound(ModSounds.ENTITY_ROTATE.get(), 1.0f, 1.0f);
                } else if (player.isShiftKeyDown()) {
                    SelectionManager.clearSelectedEntity();
                }
            }
        } else if (item instanceof RulerToolItem) {
            event.setCanceled(true);
            if (event.getLevel().isClientSide()) {
                if (player.isShiftKeyDown()) {
                    RulerState.clear();
                    player.playSound(ModSounds.RULER_CLEAR.get(), 1.0f, 1.0f);
                } else {
                    RulerState.setPointB(event.getPos());
                    player.playSound(ModSounds.RULER_POINT_B.get(), 1.0f, 1.0f);
                    showRulerDistance(player);
                }
            }
        } else if (isBrush(item)) {
            event.setCanceled(true);
            if (event.getLevel().isClientSide()) {
                applyBrush(player, event.getPos(), item);
            }
        } else if (item instanceof LaserToolItem) {
            event.setCanceled(true);
        } else if (item instanceof BlockItem) {
            // Off-grid placement. With R pressed the block is placed at the preview cell with the
            // adjusted rotation; otherwise, if the new block's cell touches an off-grid block, the
            // rotation is inherited so a rotated formation can be built block by block.
            if (event.getLevel().isClientSide()) {
                if (BlockRotateState.isActive()) {
                    event.setCanceled(true);
                    BlockPos cell = BlockRotateState.getTarget();
                    if (cell != null) {
                        ClientPackets.sendToServer(new OffGridBlockPacket(
                                cell.getX(), cell.getY(), cell.getZ(),
                                BlockRotateState.getYawDeg(), BlockRotateState.getPitchDeg(), false));
                    }
                    BlockRotateState.stop();
                    player.playSound(ModSounds.SET_CORNER_1.get(), 1.0f, 1.0f);
                } else {
                    BlockPos cell = event.getPos().relative(event.getFace());
                    float[] inherited = findInheritedRotation(player, cell);
                    if (inherited != null) {
                        event.setCanceled(true);
                        ClientPackets.sendToServer(new OffGridBlockPacket(
                                cell.getX(), cell.getY(), cell.getZ(), inherited[0], inherited[1], false));
                        player.playSound(ModSounds.SET_CORNER_1.get(), 1.0f, 1.0f);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        Item item = player.getMainHandItem().getItem();
        if (item instanceof EntityToolItem) {
            event.setCanceled(true);
            if (event.getLevel().isClientSide()) {
                EntityRotateState.stop();
                Entity target = event.getTarget();
                Entity current = SelectionManager.getSelectedEntity();
                if (current == target) {
                    SelectionManager.clearSelectedEntity();
                    player.playSound(ModSounds.ENTITY_DESELECT.get(), 1.0f, 1.0f);
                } else {
                    SelectionManager.setSelectedEntity(target);
                    player.playSound(ModSounds.ENTITY_SELECT.get(), 1.0f, 1.0f);
                }
            }
        } else if (isBuilderTool(item)) {
            // Don't let right-clicking an entity (e.g. a villager) open its GUI while a tool is held.
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------
    // Helpers for the extra tools
    // ------------------------------------------------------------------

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
    // Selection handle drag: press grabs a handle from any distance, release drops it
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || minecraft.player == null) {
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Player player = minecraft.player;
        ItemStack held = player.getMainHandItem();
        Item item = held.getItem();

        if (item instanceof SelectionToolItem && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // Grab a handle the cursor is on, from up to 64 blocks away (long reach). Holding Alt
            // turns the drag into a stretch: on release the blocks inside the selection are
            // scaled along the dragged axis to fill the new region.
            SelectionHandles.Hit handleHit = raycastHandle(player, 64.0);
            if (handleHit != null) {
                event.setCanceled(true);
                Vec3 grab = player.getEyePosition(1.0f).add(player.getLookAngle().scale(handleHit.t()));
                HandleDragState.start(handleHit.handle().axis(), handleHit.handle().positive(),
                        grab, player.getLookAngle(), isAltDown(minecraft));
                player.playSound(ModSounds.SET_CORNER_1.get(), 1.0f, 1.0f);
            }
        } else if (isBrush(item)
                && (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT || event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            // Brush clicks work at the configured reach, and with Air Placement on they also work
            // when clicking empty air (painting at the air-place distance). Cancelling the press
            // keeps the vanilla click handlers from firing a second time.
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
                event.setCanceled(true);
                applyBrush(player, target, item);
            }
        } else if (item instanceof EntityToolItem && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // Entity interaction. This uses the raw mouse press so it works on every client path,
            // and cancelling the press also stops any vanilla entity interaction (e.g. opening a
            // villager's trade GUI) from ever happening.
            HitResult hit = minecraft.hitResult;
            if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                Entity target = ((EntityHitResult) hit).getEntity();
                if (target != null) {
                    event.setCanceled(true);
                    Entity current = SelectionManager.getSelectedEntity();
                    if (EntityRotateState.isActive()) {
                        // Right-click confirms and leaves rotate mode.
                        EntityRotateState.stop();
                        player.playSound(ModSounds.ENTITY_ROTATE.get(), 1.0f, 1.0f);
                    } else if (player.isShiftKeyDown()) {
                        // Shift + right-click: deselect.
                        SelectionManager.clearSelectedEntity();
                        player.playSound(ModSounds.ENTITY_DESELECT.get(), 1.0f, 1.0f);
                    } else if (current == target) {
                        // Right-click the selected entity: grab it and free-move it with the mouse.
                        EntityRotateState.stop();
                        EntityDragState.start(target);
                        player.playSound(ModSounds.ENTITY_SELECT.get(), 1.0f, 1.0f);
                    } else {
                        SelectionManager.setSelectedEntity(target);
                        player.playSound(ModSounds.ENTITY_SELECT.get(), 1.0f, 1.0f);
                    }
                }
            }
        } else if (item instanceof BlockItem && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (BlockRotateState.isActive()) {
                // Left click is reserved for hold-to-rotate; suppress the vanilla attack.
                event.setCanceled(true);
            } else {
                // Break an off-grid block: raycast the display entities and remove the one hit.
                BlockPos cell = raycastOffGrid(player, 6.0);
                if (cell != null) {
                    event.setCanceled(true);
                    ClientPackets.sendToServer(new OffGridBlockPacket(
                            cell.getX(), cell.getY(), cell.getZ(), 0.0f, 0.0f, true));
                    player.playSound(ModSounds.SET_CORNER_2.get(), 1.0f, 1.0f);
                }
            }
        } else if (item instanceof BlockItem && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // Right-clicking a placed off-grid block places the next block beside it (on the
            // clicked face) with the same rotation, so a rotated formation can be built by
            // clicking block after block - no R needed for every piece.
            if (!BlockRotateState.isActive()) {
                OffGridHit hit = raycastOffGridHit(player, 6.0);
                if (hit != null) {
                    event.setCanceled(true);
                    BlockPos cell = hit.block.cell().relative(hit.face);
                    ClientPackets.sendToServer(new OffGridBlockPacket(
                            cell.getX(), cell.getY(), cell.getZ(),
                            hit.block.getPlacementYaw(), hit.block.getPlacementPitch(), false));
                    player.playSound(ModSounds.SET_CORNER_1.get(), 1.0f, 1.0f);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onMouseButtonPost(InputEvent.MouseButton.Post event) {
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && event.getAction() == GLFW.GLFW_RELEASE
                && EntityDragState.isDragging()) {
            EntityDragState.stop();
            Player dragPlayer = Minecraft.getInstance().player;
            if (dragPlayer != null) {
                dragPlayer.playSound(ModSounds.ENTITY_MOVE.get(), 1.0f, 1.0f);
            }
            return;
        }
        if (!HandleDragState.isDragging()) {
            return;
        }
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT || event.getAction() != GLFW.GLFW_RELEASE) {
            return;
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
            return;
        }
        if (stretch) {
            if (moved && origMin != null && origMax != null && SelectionManager.hasSelection()) {
                ClientPackets.sendToServer(StretchPacket.create(axis.ordinal(), positive,
                        origMin, origMax, SelectionManager.getMin(), SelectionManager.getMax()));
            }
            return;
        }
        player.playSound(ModSounds.SET_CORNER_2.get(), 1.0f, 1.0f);
    }

    /** Whether the left or right Alt key is currently held. */
    private static boolean isAltDown(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    // ------------------------------------------------------------------
    // Scroll wheel: rotate or nudge the selected entity
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }
        Item held = player.getMainHandItem().getItem();
        if (isBrush(held) && BuilderSettings.isAirPlacement()) {
            // "Use the mouse scroll wheel to increase/decrease place distance."
            event.setCanceled(true);
            double delta = Math.signum(event.getDeltaY());
            float next = Math.round((BuilderSettings.getAirPlaceDistance() + (float) delta) * 2) / 2f;
            BuilderSettings.setAirPlaceDistance(Math.max(3.0f, Math.min(32.0f, next)));
            player.displayClientMessage(Component.literal(
                    "Air placement distance: " + BuilderSettings.getAirPlaceDistance()), true);
            return;
        }
        if (!(held instanceof EntityToolItem)) {
            return;
        }
        Entity entity = SelectionManager.getSelectedEntity();
        if (entity == null || entity.isRemoved()) {
            return;
        }

        event.setCanceled(true);
        double delta = Math.signum(event.getDeltaY());
        if (player.isShiftKeyDown()) {
            // Shift + scroll: move one block up/down.
            ClientPackets.sendToServer(new EntityTransformPacket(
                    entity.getId(),
                    entity.getX(),
                    entity.getY() + delta,
                    entity.getZ(),
                    entity.getYRot(),
                    entity.getXRot(),
                    false));
        } else {
            // Scroll: move the entity closer (up) or farther (down) along the camera axis,
            // Hytale-style - it stays on the ray under the cursor.
            Vec3 eye = player.getEyePosition(1.0f);
            Vec3 to = entity.position().subtract(eye);
            double dist = Math.max(0.5, to.length() - delta);
            Vec3 pos = eye.add(to.normalize().scale(dist));
            ClientPackets.sendToServer(new EntityTransformPacket(
                    entity.getId(),
                    pos.x,
                    pos.y,
                    pos.z,
                    entity.getYRot(),
                    entity.getXRot(),
                    false));
        }
        player.playSound(ModSounds.ENTITY_MOVE.get(), 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------
    // Keybindings
    // ------------------------------------------------------------------

    /**
     * Keeps No Clip working: {@code Player.tick()} resets {@code noPhysics} every tick (it is
     * derived from spectator mode). The PlayerMixin re-applies it right after the reset and before
     * movement runs for that tick; this extra re-apply is a harmless safety net.
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;
        if (player.level().isClientSide() && BuilderSettings.isNoClip()) {
            player.noPhysics = true;
        }
    }

    /** Renders the control-hints legend in the corner while a builder tool is held.
     *  Called from the GuiMixin at the end of {@code Gui.render} (Forge 1.21.1 removed
     *  {@code RenderGuiEvent}). */
    public static void renderLegend(GuiGraphics graphics) {
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
                    "scroll closer/farther · sneak+scroll up/down · E interface",
                    "X delete · J dup · G freeze"};
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
     * In creative mode, pressing E opens the Creative Settings window instead of the
     * vanilla inventory (the vanilla creative item picker is reachable from a button inside it).
     */
    @SubscribeEvent
    public static void onClientTickPre(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // While the off-grid placement preview is up, Enter confirms the placement instead of
        // opening chat. Swallowing the chat key here (before the game's own consumeClick runs)
        // keeps Enter from opening the chat screen.
        if (minecraft.player != null && minecraft.screen == null && BlockRotateState.isActive()) {
            while (minecraft.options.keyChat.consumeClick()) {
            }
        }
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (minecraft.gameMode == null || !minecraft.gameMode.hasInfiniteItems()) {
            return;
        }
        if (minecraft.options.keyInventory.consumeClick()) {
            if (minecraft.player.getMainHandItem().getItem() instanceof EntityToolItem) {
                // E with the Entity Tool held opens its Hytale-style spawn/rotate interface.
                minecraft.setScreen(new EntityToolScreen((net.minecraft.client.player.LocalPlayer) minecraft.player));
            } else {
                minecraft.setScreen(new CreativeSettingsScreen((net.minecraft.client.player.LocalPlayer) minecraft.player));
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
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

        if (minecraft.screen != null) {
            HandleDragState.stop(false);

            EntityRotateState.stop();
            BlockRotateState.stop();
            return;
        }
        ItemStack held = player.getMainHandItem();

        // While a handle is being dragged, track the mouse ray against the drag plane.
        // Press/release are handled by the MouseButton events above; here the face just follows
        // the cursor every tick until the button is released.
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
                    player.playSound(ModSounds.ENTITY_ROTATE.get(), 1.0f, 1.0f);
                } else {
                    Entity entity = SelectionManager.getSelectedEntity();
                    if (entity != null && !entity.isRemoved()) {
                        // R: enter rotate mode (Alt+R rotates only the head).
                        EntityDragState.stop();
                        EntityRotateState.start(entity, isAltDown(minecraft));
                        player.playSound(ModSounds.ENTITY_ROTATE.get(), 1.0f, 1.0f);
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
                    player.playSound(ModSounds.SET_CORNER_2.get(), 1.0f, 1.0f);
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
                    player.playSound(ModSounds.SET_CORNER_1.get(), 1.0f, 1.0f);
                }
            }
            while (KeyBindings.CONFIRM.consumeClick()) {
                // Enter: place the block at the preview cell with the current rotation.
                BlockPos cell = BlockRotateState.getTarget();
                if (cell != null) {
                    ClientPackets.sendToServer(new OffGridBlockPacket(
                            cell.getX(), cell.getY(), cell.getZ(),
                            BlockRotateState.getYawDeg(), BlockRotateState.getPitchDeg(), false));
                    player.playSound(ModSounds.SET_CORNER_1.get(), 1.0f, 1.0f);
                }
                BlockRotateState.stop();
            }
        }
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
            return block;
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
}
