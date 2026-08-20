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
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.buildertools.util.OffGridTransform;
import net.minecraft.world.level.block.state.BlockState;
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

import static net.buildertools.BuilderToolsMod.MODID;

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
                // Right-clicking a block never moves the selected entity anywhere; entity selection
                // and free-move dragging happen on the entity hit itself (the raw mouse hook).
                if (level.isClientSide()) {
                    if (EntityRotateState.isActive()) {
                        // Right-click confirms and leaves rotate mode (Hytale "cancel movement").
                        EntityRotateState.stop();
                        player.playSound(ModSounds.ENTITY_ROTATE, 1.0f, 1.0f);
                    } else if (player.isShiftKeyDown()) {
                        SelectionManager.clearSelectedEntity();
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
                // adjusted rotation; otherwise, clicking ON an off-grid block places a new block into
                // the cell next to it (inheriting its rotation), and clicking a normal block whose
                // target cell touches an off-grid block inherits the rotation too, so a rotated
                // formation can be built block by block.
                if (level.isClientSide()) {
                    if (BlockRotateState.isActive()) {
                        BlockPos cell = BlockRotateState.getTarget();
                        if (cell != null) {
                            Vec3 center = BlockRotateState.getCenter();
                            ClientPackets.sendToServer(new OffGridBlockPacket(
                                    center.x, center.y, center.z,
                                    BlockRotateState.getYawDeg(), BlockRotateState.getPitchDeg(), false,
                                    BlockRotateState.isBillboard()));
                        }
                        BlockRotateState.stop();
                        player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                    } else {
                        // 1) Aiming at an off-grid block (its cell is air, so the vanilla block
                        // raycast would pass through it and hit the block behind): place a new
                        // block FLUSH against the clicked rotated face, inheriting the rotation,
                        // so a stratum of rotated blocks can be built side by side. Only when the
                        // entity is closer than the vanilla block hit, so clicking a real wall in
                        // front of an off-grid block still behaves normally.
                        OffGridHit ogHit = raycastOffGridHit(player, 6.0);
                        if (ogHit != null && ogHit.distSq < eyeDistSq(player, hitResult.getLocation())) {
                            Vec3 center = flushPlacementCenter(ogHit.block, ogHit.normal);
                            if (canPlaceOffGrid(player, center, ogHit.block.getPlacementYaw(), ogHit.block.getPlacementPitch())) {
                                ClientPackets.sendToServer(new OffGridBlockPacket(
                                        center.x, center.y, center.z,
                                        ogHit.block.getPlacementYaw(), ogHit.block.getPlacementPitch(), false,
                                        ogHit.block.isBillboard()));
                                player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                                return InteractionResult.FAIL;
                            }
                        } else {
                            // 2) Normal block click: inherit the rotation from an off-grid NEIGHBOR
                            // only (never the target cell itself, which would re-plant it).
                            BlockPos cell = hitResult.getBlockPos().relative(hitResult.getDirection());
                            float[] inherited = findInheritedRotation(player, cell);
                            if (inherited != null) {
                                Vec3 center = Vec3.atCenterOf(cell);
                                if (canPlaceOffGrid(player, center, inherited[0], inherited[1])) {
                                    ClientPackets.sendToServer(new OffGridBlockPacket(
                                            center.x, center.y, center.z, inherited[0], inherited[1], false,
                                            inherited[2] == 1.0f));
                                    player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                                    return InteractionResult.FAIL;
                                }
                            }
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
                    EntityRotateState.stop();
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
            } else if (item instanceof BlockItem && target instanceof OffGridBlockEntity ogBlock) {
                // Right-clicking an off-grid block directly (entity hit, not a block hit): place a
                // new block FLUSH against the clicked rotated face, inheriting the rotation, so a
                // stratum of rotated blocks can be built side by side.
                if (level.isClientSide()) {
                    OffGridHit ogHit = raycastOffGridHit(player, 6.0);
                    Vec3 normal = ogHit != null ? ogHit.normal : faceFromLook(player, ogBlock);
                    Vec3 center = flushPlacementCenter(ogBlock, normal);
                    if (canPlaceOffGrid(player, center, ogBlock.getPlacementYaw(), ogBlock.getPlacementPitch())) {
                        ClientPackets.sendToServer(new OffGridBlockPacket(
                                center.x, center.y, center.z,
                                ogBlock.getPlacementYaw(), ogBlock.getPlacementPitch(), false,
                                ogBlock.isBillboard()));
                        player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
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
            if (minecraft.player == null || minecraft.gui.screen() != null) {
                return;
            }
            // While the off-grid placement preview is up, Enter confirms the placement instead of
            // opening chat. Swallowing the chat key here (before the game's own consumeClick runs)
            // keeps Enter from opening the chat screen.
            if (BlockRotateState.isActive()) {
                while (minecraft.options.keyChat.consumeClick()) {
                }
            }
            if (!minecraft.player.getAbilities().instabuild) {
                return;
            }
            if (minecraft.options.keyInventory.consumeClick()) {
                if (minecraft.player.getMainHandItem().getItem() instanceof EntityToolItem) {
                    // E with the Entity Tool held opens its Hytale-style spawn/rotate interface.
                    minecraft.gui.setScreen(new EntityToolScreen((LocalPlayer) minecraft.player));
                } else {
                    minecraft.gui.setScreen(new CreativeSettingsScreen((LocalPlayer) minecraft.player));
                }
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(ClientEvents::onEndTick);

        // 26.2 HUD: the legend is registered as a HudElement drawn before the vanilla elements.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MODID, "legend"),
                (extractor, deltaTracker) -> renderLegend(extractor));
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
        ((LocalPlayer) player).sendOverlayMessage(Component.literal(String.format(Locale.ROOT,
                "Ruler: %.1f blocks  (X %d, Y %d, Z %d)",
                a.distanceTo(b),
                Math.abs(pb.getX() - pa.getX()),
                Math.abs(pb.getY() - pa.getY()),
                Math.abs(pb.getZ() - pa.getZ()))));
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
        if (minecraft.gui.screen() != null || minecraft.player == null) {
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
        } else if (left && !isBuilderTool(item) && !BlockRotateState.isActive()) {
            // Mine an off-grid block like a normal block with ANY item (or an empty hand):
            // creative breaks instantly; in survival the dig is progressive (progress accumulates
            // while LMB is held and the cursor stays on the block - see OffGridMining.tick)
            // instead of dropping it on the first hit like a painting.
            OffGridBlockEntity mineTarget = raycastOffGridBlock(player, 6.0);
            if (mineTarget != null) {
                if (player.getAbilities().instabuild) {
                    Vec3 c = mineTarget.modelCenter();
                    ClientPackets.sendToServer(new OffGridBlockPacket(
                            c.x, c.y, c.z, 0.0f, 0.0f, true, false));
                    player.playSound(ModSounds.SET_CORNER_2, 1.0f, 1.0f);
                } else {
                    OffGridMining.start(mineTarget);
                }
                return true;
            }
            return false;
        } else if (item instanceof BlockItem && left) {
            if (BlockRotateState.isActive()) {
                // Left click is reserved for hold-to-rotate; suppress the vanilla attack.
                return true;
            }
            return false;
        } else if (item instanceof BlockItem && right) {
            // Right-clicking a placed off-grid block places the next block flush against the
            // clicked rotated face with the same rotation, so a rotated formation can be built
            // by clicking block after block - no R needed for every piece.
            if (!BlockRotateState.isActive()) {
                OffGridHit hit = raycastOffGridHit(player, 6.0);
                if (hit != null) {
                    Vec3 center = flushPlacementCenter(hit.block, hit.normal);
                    if (canPlaceOffGrid(player, center, hit.block.getPlacementYaw(), hit.block.getPlacementPitch())) {
                        ClientPackets.sendToServer(new OffGridBlockPacket(
                                center.x, center.y, center.z,
                                hit.block.getPlacementYaw(), hit.block.getPlacementPitch(), false,
                                hit.block.isBillboard()));
                        player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                        return true;
                    }
                }
            }
            return false;
        }
        return false;
    }

    /** Whether the left or right Alt key is currently held. */
    private static boolean isAltDown(Minecraft minecraft) {
        long window = minecraft.getWindow().handle();
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
        if (player == null || minecraft.gui.screen() != null) {
            return false;
        }
        Item held = player.getMainHandItem().getItem();
        if (isBrush(held) && BuilderSettings.isAirPlacement()) {
            // "Use the mouse scroll wheel to increase/decrease place distance."
            double delta = Math.signum(deltaY);
            float next = Math.round((BuilderSettings.getAirPlaceDistance() + (float) delta) * 2) / 2f;
            BuilderSettings.setAirPlaceDistance(Math.max(3.0f, Math.min(32.0f, next)));
            ((LocalPlayer) player).sendOverlayMessage(Component.literal(
                    "Air placement distance: " + BuilderSettings.getAirPlaceDistance()));
            return true;
        }
        Entity entity;
        if (held instanceof EntityToolItem) {
            entity = SelectionManager.getSelectedEntity();
            if (entity == null || entity.isRemoved()) {
                // Nothing selected: zoom the off-grid block under the cursor.
                entity = raycastOffGridBlock(player, 6.0);
            }
        } else if (held instanceof BlockItem) {
            // With a block in hand, zoom the off-grid block under the cursor instead of
            // scrolling the hotbar (Hytale-style Entity Tool zoom).
            entity = raycastOffGridBlock(player, 6.0);
        } else {
            return false;
        }
        if (entity == null || entity.isRemoved()) {
            return false;
        }

        double delta = Math.signum(deltaY);
        if (player.isShiftKeyDown()) {
            // Shift + scroll: move one block up/down (from the model center for off-grid blocks,
            // so the fractional position stays consistent).
            Vec3 base = entity instanceof OffGridBlockEntity og ? og.modelCenter() : entity.position();
            ClientPackets.sendToServer(new EntityTransformPacket(
                    entity.getId(),
                    base.x,
                    base.y + delta,
                    base.z,
                    entity.getYRot(),
                    entity.getXRot(),
                    false));
        } else {
            // Scroll: move the entity closer or farther along the camera axis, Hytale-style - it
            // stays on the ray under the cursor. Off-grid blocks zoom from their MODEL center
            // (fractional, off the grid) so both directions move smoothly; the server keeps that
            // exact position instead of snapping it back to a grid cell.
            Vec3 eye = player.getEyePosition(1.0f);
            Vec3 base = entity instanceof OffGridBlockEntity og ? og.modelCenter() : entity.position();
            Vec3 to = base.subtract(eye);
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
        player.playSound(ModSounds.ENTITY_MOVE, 1.0f, 1.0f);
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

        if (minecraft.gui.screen() != null) {
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

        // Progressive mining of an off-grid block (survival): holding LMB on it digs it like a
        // normal block - progress accumulates while the button is held and the cursor stays on
        // the block, and it breaks (dropping its item) when the bar fills.
        if (OffGridMining.tick(player)) {
            Vec3 c = OffGridMining.getTarget().modelCenter();
            ClientPackets.sendToServer(new OffGridBlockPacket(
                    c.x, c.y, c.z, 0.0f, 0.0f, true, false));
            player.playSound(ModSounds.SET_CORNER_2, 1.0f, 1.0f);
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
                    // can be spun strictly in place (keeping its fractional center); otherwise
                    // start the placement preview.
                    OffGridBlockEntity placed = raycastOffGridBlock(player, 6.0);
                    if (placed != null) {
                        BlockRotateState.start(player, placed.cell(), placed.modelCenter(),
                                placed.getPlacementYaw(), placed.getPlacementPitch(),
                                placed.getRepresentedState());
                    } else {
                        BlockRotateState.start(player);
                    }
                    player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                }
            }
            while (KeyBindings.CONFIRM.consumeClick()) {
                // Enter: place the block at the preview position with the current rotation.
                BlockPos cell = BlockRotateState.getTarget();
                if (cell != null) {
                    Vec3 center = BlockRotateState.getCenter();
                    ClientPackets.sendToServer(new OffGridBlockPacket(
                            center.x, center.y, center.z,
                            BlockRotateState.getYawDeg(), BlockRotateState.getPitchDeg(), false,
                            BlockRotateState.isBillboard()));
                    player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                }
                BlockRotateState.stop();
            }
            while (KeyBindings.BILLBOARD.consumeClick()) {
                // B: toggle the player-facing billboard mode of the placement preview.
                if (BlockRotateState.isActive()) {
                    BlockRotateState.toggleBillboard();
                    player.playSound(ModSounds.SET_CORNER_1, 1.0f, 1.0f);
                }
            }
        }
    }

    /** Renders the control-hints legend in the corner while a builder tool is held. */
    private static void renderLegend(GuiGraphicsExtractor graphics) {
        if (!BuilderSettings.isDisplayLegend()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.gui.screen() != null) {
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
            graphics.text(minecraft.font, line, x, y, 0xFFE8E8E8);
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
     * Finds the rotation inherited from an off-grid block ADJACENT to the given cell (never the
     * cell itself - that would re-plant the block already occupying it). Returns null when there
     * is no off-grid neighbor, so normal grid placement proceeds.
     */
    /**
     * Finds the rotation (and billboard flag) inherited from an off-grid block ADJACENT to the
     * given cell (never the cell itself - that would re-plant the block already occupying it).
     * Returns {@code {yaw, pitch, billboard?1:0}} or null when there is no off-grid neighbor, so
     * normal grid placement proceeds.
     */
    private static float[] findInheritedRotation(Player player, BlockPos cell) {
        List<BlockPos> cells = List.of(
                cell.above(), cell.below(), cell.north(), cell.south(), cell.east(), cell.west());
        for (BlockPos neighbor : cells) {
            OffGridBlockEntity block = findOffGridEntity(player.level(), neighbor);
            if (block != null) {
                return new float[]{block.getPlacementYaw(), block.getPlacementPitch(),
                        block.isBillboard() ? 1.0f : 0.0f};
            }
        }
        return null;
    }

    /** Returns the solid off-grid block occupying the given cell, or null (client-side query). */
    private static OffGridBlockEntity findOffGridEntity(net.minecraft.world.level.Level level, BlockPos pos) {
        for (OffGridBlockEntity block : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                        pos.getX() + 2, pos.getY() + 2, pos.getZ() + 2))) {
            if (block.cell().equals(pos)) {
                return block;
            }
        }
        return null;
    }

    /**
     * Raycasts the solid off-grid block entities along the look direction and returns the nearest
     * hit against the block's ACTUAL rotated model (its oriented bounding box, not the axis-aligned
     * collision box), so the hit face is the real visible side. Used to break/re-rotate/place
     * against a block by looking at it.
     */
    private static OffGridBlockEntity raycastOffGridBlock(Player player, double reach) {
        OffGridHit hit = raycastOffGridHit(player, reach);
        return hit != null ? hit.block : null;
    }

    /** The solid off-grid block under the cursor, the world-space face normal of the rotated
     *  model it hit, the exact hit point and the squared distance from the eye. */
    private record OffGridHit(OffGridBlockEntity block, Vec3 normal, Vec3 hitPoint, double distSq) {
    }

    private static OffGridHit raycastOffGridHit(Player player, double reach) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 dir = player.getLookAngle();
        double best = Double.MAX_VALUE;
        OffGridHit bestHit = null;
        // Entity tags are not synced to clients, so the entity class alone identifies off-grid
        // blocks here (all OffGridBlockEntity instances are off-grid blocks).
        for (OffGridBlockEntity block : minecraft.level.getEntitiesOfClass(OffGridBlockEntity.class,
                player.getBoundingBox().expandTowards(dir.scale(reach)).inflate(1.5))) {
            OffGridHit hit = intersectVisual(block, eye, dir, reach);
            if (hit != null && hit.distSq < best) {
                best = hit.distSq;
                bestHit = hit;
            }
        }
        return bestHit;
    }

    /**
     * Ray vs the block's actual rotated model (the OBB of its shape rotated around the model
     * center - the same box the rendered display spans). Returns the entry point, the world-space
     * outward face normal at that face, and the squared eye distance, or null when missed.
     */
    private static OffGridHit intersectVisual(OffGridBlockEntity block, Vec3 eye, Vec3 dir, double reach) {
        Vec3 center = block.modelCenter();
        org.joml.Quaternionf rot = net.buildertools.util.OffGridTransform.rotation(
                block.getPlacementYaw(), block.getPlacementPitch());
        org.joml.Quaternionf inv = rot.conjugate();
        // Ray transformed into model space: the box is axis-aligned around the origin and spans
        // the block's shape bounds.
        org.joml.Vector3f o = inv.transform(new org.joml.Vector3f(
                (float) (eye.x - center.x), (float) (eye.y - center.y), (float) (eye.z - center.z)),
                new org.joml.Vector3f());
        org.joml.Vector3f d = inv.transform(new org.joml.Vector3f(
                (float) dir.x, (float) dir.y, (float) dir.z), new org.joml.Vector3f());
        Minecraft minecraft = Minecraft.getInstance();
        AABB shape = block.getRepresentedState().getCollisionShape(minecraft.level, BlockPos.ZERO).bounds();
        double minX = shape.minX - 0.5, maxX = shape.maxX - 0.5;
        double minY = shape.minY - 0.5, maxY = shape.maxY - 0.5;
        double minZ = shape.minZ - 0.5, maxZ = shape.maxZ - 0.5;

        // Slab method, tracking the entry face (low or high plane per axis).
        double tmin = 0.0, tmax = reach;
        int entryAxis = -1;
        boolean entryLow = false;
        for (int i = 0; i < 3; i++) {
            double oi = i == 0 ? o.x : i == 1 ? o.y : o.z;
            double di = i == 0 ? d.x : i == 1 ? d.y : d.z;
            double lo = i == 0 ? minX : i == 1 ? minY : minZ;
            double hi = i == 0 ? maxX : i == 1 ? maxY : maxZ;
            if (Math.abs(di) < 1.0E-8) {
                if (oi < lo || oi > hi) {
                    return null;
                }
                continue;
            }
            double tLow = (lo - oi) / di;
            double tHigh = (hi - oi) / di;
            boolean lowIsEntry = tLow < tHigh;
            if (!lowIsEntry) {
                double tmp = tLow;
                tLow = tHigh;
                tHigh = tmp;
            }
            if (tLow > tmin) {
                tmin = tLow;
                entryAxis = i;
                entryLow = lowIsEntry;
            }
            if (tHigh < tmax) {
                tmax = tHigh;
            }
            if (tmin > tmax) {
                return null;
            }
        }
        if (entryAxis < 0) {
            return null;
        }

        Vec3 entry = eye.add(dir.scale(tmin));
        org.joml.Vector3f face = switch (entryAxis) {
            case 0 -> new org.joml.Vector3f(entryLow ? -1 : 1, 0, 0);
            case 1 -> new org.joml.Vector3f(0, entryLow ? -1 : 1, 0);
            default -> new org.joml.Vector3f(0, 0, entryLow ? -1 : 1);
        };
        org.joml.Vector3f normal = rot.transform(face, new org.joml.Vector3f());
        return new OffGridHit(block, new Vec3(normal.x, normal.y, normal.z), entry,
                eye.distanceToSqr(entry));
    }

    private static double eyeDistSq(Player player, Vec3 point) {
        return player.getEyePosition(1.0f).distanceToSqr(point);
    }

    /**
     * The center for a new block placed FLUSH against the clicked face of an off-grid block: the
     * neighbor center is the existing center plus the rotated face normal scaled by the block's
     * thickness along that axis, so the two models touch exactly (like vanilla adjacency, but
     * along the rotated faces).
     */
    private static Vec3 flushPlacementCenter(OffGridBlockEntity block, Vec3 worldNormal) {
        Vec3 center = block.modelCenter();
        org.joml.Quaternionf rot = net.buildertools.util.OffGridTransform.rotation(
                block.getPlacementYaw(), block.getPlacementPitch());
        org.joml.Vector3f local = rot.conjugate().transform(new org.joml.Vector3f(
                (float) worldNormal.x, (float) worldNormal.y, (float) worldNormal.z),
                new org.joml.Vector3f());
        Minecraft minecraft = Minecraft.getInstance();
        AABB shape = block.getRepresentedState().getCollisionShape(minecraft.level, BlockPos.ZERO).bounds();
        float ax = Math.abs(local.x), ay = Math.abs(local.y), az = Math.abs(local.z);
        double thickness;
        if (ax >= ay && ax >= az) {
            thickness = shape.getXsize();
        } else if (ay >= az) {
            thickness = shape.getYsize();
        } else {
            thickness = shape.getZsize();
        }
        return center.add(worldNormal.x * thickness, worldNormal.y * thickness, worldNormal.z * thickness);
    }

    /**
     * Whether a new off-grid block centered at {@code center} can be placed: the spot is not
     * already occupied by another off-grid block, no existing off-grid block's ACTUAL rotated
     * model penetrates the new one (touching is fine - flush-adjacent rotated blocks are legal),
     * the vanilla block there is replaceable, and the player is not standing inside it. (The
     * server re-validates authoritatively.)
     */
    private static boolean canPlaceOffGrid(Player player, Vec3 center, float yaw, float pitch) {
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        AABB newShape = state.getCollisionShape(player.level(), BlockPos.ZERO).bounds();
        for (OffGridBlockEntity other : player.level().getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(center.x - 2, center.y - 2, center.z - 2,
                        center.x + 2, center.y + 2, center.z + 2))) {
            if (other.modelCenter().distanceToSqr(center) < 0.0025) {
                return false; // the exact same spot - a re-rotate, not a new placement
            }
            if (OffGridTransform.modelsOverlap(
                    center.x, center.y, center.z, yaw, pitch, newShape,
                    other.modelCenter().x, other.modelCenter().y, other.modelCenter().z,
                    other.getPlacementYaw(), other.getPlacementPitch(),
                    other.getRepresentedState().getCollisionShape(player.level(), BlockPos.ZERO).bounds())) {
                return false;
            }
        }
        AABB footprint = OffGridTransform.boxAround(center.x, center.y, center.z, yaw, pitch, newShape);
        return !player.getBoundingBox().intersects(footprint);
    }

    /** The off-grid block whose model center is within 0.6 of the given point, or null. */
    private static OffGridBlockEntity findOffGridEntityAt(net.minecraft.world.level.Level level, Vec3 center) {
        for (OffGridBlockEntity block : level.getEntitiesOfClass(OffGridBlockEntity.class,
                new AABB(center.x - 0.75, center.y - 0.75, center.z - 0.75,
                        center.x + 0.75, center.y + 0.75, center.z + 0.75))) {
            if (block.modelCenter().distanceToSqr(center) < 0.36) {
                return block;
            }
        }
        return null;
    }

    /** The general direction from the player's eye to the block center (used for entity hits). */
    private static Vec3 faceFromLook(Player player, OffGridBlockEntity block) {
        Vec3 delta = block.modelCenter().subtract(player.getEyePosition(1.0f));
        return delta.normalize();
    }
}
