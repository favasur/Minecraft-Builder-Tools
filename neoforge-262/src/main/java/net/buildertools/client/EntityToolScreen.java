package net.buildertools.client;

import net.buildertools.network.packet.EntitySpawnPacket;
import net.buildertools.network.packet.EntityTransformPacket;
import net.buildertools.selection.SelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Entity Tool's Hytale-style interface (opened with E while the Entity Tool is held): a
 * searchable list of entities to spawn in front of the player, plus live yaw/pitch sliders that
 * rotate the currently selected entity (including off-grid blocks) in place.
 */
@OnlyIn(Dist.CLIENT)
public final class EntityToolScreen extends Screen {
    private static final int PANEL_W = 280;
    private static final int LIST_TOP = 64;

    private final Player player;
    private final List<EntityType<?>> allTypes = new ArrayList<>();
    private final List<EntityType<?>> filtered = new ArrayList<>();
    private final List<Control> controls = new ArrayList<>();

    private EditBox search;
    private int panelX;
    private int panelY;
    private int panelH;
    private int listBottom;
    private int scrollOffset;
    private int maxScroll;

    private Slider yawSlider;
    private Slider pitchSlider;

    public EntityToolScreen(Player player) {
        super(Component.literal("Entity Tool"));
        this.player = player;
    }

    @Override
    protected void init() {
        allTypes.clear();
        Registry<EntityType<?>> registry =
                Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE);
        for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> entry : registry.entrySet()) {
            if (entry.getValue() != EntityTypes.PLAYER) {
                allTypes.add(entry.getValue());
            }
        }
        allTypes.sort(Comparator.comparing(t -> Component.translatable(t.getDescriptionId()).getString()
                .toLowerCase(Locale.ROOT)));

        panelX = (this.width - PANEL_W) / 2;
        panelY = 16;
        panelH = Math.min(this.height - 32, 360);
        listBottom = panelY + panelH - 74;

        search = new EditBox(this.font, panelX + 10, panelY + 30, PANEL_W - 20, 16,
                Component.literal("Search"));
        search.setResponder(s -> {
            applyFilter();
            scrollOffset = 0;
        });
        this.addRenderableWidget(search);

        controls.clear();
        int x = panelX + 10;
        int w = PANEL_W - 20;

        // Selected-entity rotation panel (bottom of the window).
        yawSlider = new Slider("Yaw", -180, 180, 1, currentYaw(), this::setYaw, x, listBottom + 14, w);
        pitchSlider = new Slider("Pitch", -90, 90, 1, currentPitch(), this::setPitch, x, listBottom + 40, w);
        controls.add(yawSlider);
        controls.add(pitchSlider);

        applyFilter();
        search.setFocused(true);
    }

    private void applyFilter() {
        filtered.clear();
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        for (EntityType<?> type : allTypes) {
            String name = Component.translatable(type.getDescriptionId()).getString();
            String id = EntityType.getKey(type).getPath();
            if (query.isEmpty() || name.toLowerCase(Locale.ROOT).contains(query) || id.contains(query)) {
                filtered.add(type);
            }
        }
        int rows = filtered.size();
        int visible = Math.max(1, (listBottom - LIST_TOP - panelY) / 12);
        maxScroll = Math.max(0, rows - visible);
    }

    // ------------------------------------------------------------------
    // Rendering (26.2: extractRenderState + GuiGraphicsExtractor)
    // ------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.fill(panelX - 4, panelY - 4, panelX + PANEL_W + 4, panelY + panelH + 4, 0xEE070C16);
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xF0122038);
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + 2, 0xFF3A5A8C);
        graphics.fill(panelX, panelY + panelH - 2, panelX + PANEL_W, panelY + panelH, 0xFF3A5A8C);
        graphics.fill(panelX, panelY, panelX + 2, panelY + panelH, 0xFF3A5A8C);
        graphics.fill(panelX + PANEL_W - 2, panelY, panelX + PANEL_W, panelY + panelH, 0xFF3A5A8C);
        graphics.text(this.font, "Entity Tool", panelX + 10, panelY + 8, 0xFFFFFFFF);
        graphics.fill(panelX + 8, panelY + 24, panelX + PANEL_W - 8, panelY + 25, 0xFF2A3F66);

        // Scrollable entity list.
        int y = panelY + LIST_TOP;
        graphics.enableScissor(panelX, panelY + LIST_TOP, panelX + PANEL_W, listBottom);
        graphics.pose().pushMatrix();
        graphics.pose().translate(0, -scrollOffset * 12);
        for (EntityType<?> type : filtered) {
            boolean hovered = mouseX >= panelX + 2 && mouseX <= panelX + PANEL_W - 2
                    && mouseY >= y && mouseY <= y + 11;
            if (hovered) {
                graphics.fill(panelX + 2, y, panelX + PANEL_W - 2, y + 12, 0xFF2E6BE6);
            }
            String name = Component.translatable(type.getDescriptionId()).getString();
            graphics.text(this.font, name, panelX + 10, y + 2, hovered ? 0xFFFFFFFF : 0xFFB8C7D9);
            y += 12;
        }
        graphics.pose().popMatrix();
        graphics.disableScissor();

        // Selected entity info + rotation sliders.
        Entity selected = SelectionManager.getSelectedEntity();
        String info = selected != null && !selected.isRemoved()
                ? Component.translatable(selected.getType().getDescriptionId()).getString()
                : "(nothing selected)";
        graphics.text(this.font, "Selected: " + info, panelX + 10, listBottom + 2, 0xFF7FA8E0);
        yawSlider.setEnabled(selected != null && !selected.isRemoved());
        pitchSlider.setEnabled(selected != null && !selected.isRemoved());
        for (Control control : controls) {
            control.render(graphics, mouseX, mouseY);
        }

        // Scrollbar.
        if (maxScroll > 0) {
            int trackTop = panelY + LIST_TOP;
            int trackH = listBottom - trackTop;
            int thumbH = Math.max(14, trackH * trackH / (filtered.size() * 12));
            int thumbY = trackTop + (trackH - thumbH) * scrollOffset / maxScroll;
            int sx = panelX + PANEL_W - 5;
            graphics.fill(sx, trackTop, sx + 3, trackTop + trackH, 0xFF0A1424);
            graphics.fill(sx, thumbY, sx + 3, thumbY + thumbH, 0xFF4A7CF7);
        }
    }

    // ------------------------------------------------------------------
    // Input (26.2: MouseButtonEvent)
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean inside) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (mouseX >= panelX + 2 && mouseX <= panelX + PANEL_W - 2
                && mouseY >= panelY + LIST_TOP && mouseY <= listBottom && event.button() == 0) {
            int row = (int) ((mouseY - (panelY + LIST_TOP)) / 12) + scrollOffset;
            if (row >= 0 && row < filtered.size()) {
                spawn(filtered.get(row));
                return true;
            }
        }
        for (Control control : controls) {
            if (control.mouseClicked(mouseX, mouseY, event.button())) {
                return true;
            }
        }
        return super.mouseClicked(event, inside);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        for (Control control : controls) {
            control.mouseDragged(event.x(), event.y(), event.button(), dragX, dragY);
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        for (Control control : controls) {
            control.mouseReleased(event.x(), event.y(), event.button());
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX >= panelX && mouseX <= panelX + PANEL_W
                && mouseY >= panelY + LIST_TOP && mouseY <= listBottom) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.round(deltaY)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void spawn(EntityType<?> type) {
        Minecraft minecraft = Minecraft.getInstance();
        Player p = minecraft.player;
        if (p == null) {
            return;
        }
        Vec3 pos = p.getEyePosition(1.0f).add(p.getLookAngle().scale(4.0));
        // Spawn at the center of the block the player is looking at.
        ClientPackets.sendToServer(new EntitySpawnPacket(EntityType.getKey(type),
                Math.floor(pos.x) + 0.5, Math.floor(pos.y), Math.floor(pos.z) + 0.5));
        p.playSound(net.buildertools.registry.ModSounds.SET_CORNER_1.get(), 1.0f, 1.0f);
    }

    private float currentYaw() {
        Entity entity = SelectionManager.getSelectedEntity();
        return entity != null ? Math.round(entity.getYRot() / 5.0f) * 5.0f : 0.0f;
    }

    private float currentPitch() {
        Entity entity = SelectionManager.getSelectedEntity();
        return entity != null ? Math.round(entity.getXRot() / 5.0f) * 5.0f : 0.0f;
    }

    private void setYaw(double yaw) {
        rotateSelected((float) yaw, null);
    }

    private void setPitch(double pitch) {
        rotateSelected(null, (float) pitch);
    }

    private void rotateSelected(Float yaw, Float pitch) {
        Entity entity = SelectionManager.getSelectedEntity();
        if (entity == null || entity.isRemoved()) {
            return;
        }
        ClientPackets.sendToServer(new EntityTransformPacket(
                entity.getId(), entity.getX(), entity.getY(), entity.getZ(),
                yaw != null ? yaw : entity.getYRot(),
                pitch != null ? pitch : entity.getXRot(),
                false));
    }

    // ------------------------------------------------------------------
    // Controls (sliders only - same style as the Creative settings panel)
    // ------------------------------------------------------------------

    private interface Control {
        void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

        default boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }

        default void mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        }

        default void mouseReleased(double mouseX, double mouseY, int button) {
        }
    }

    private final class Slider implements Control {
        private final String label;
        private final double min;
        private final double max;
        private final double step;
        private final Consumer<Double> onChange;
        private final int x;
        private final int y;
        private final int w;
        private double value;
        private boolean dragging;
        private boolean enabled = true;

        Slider(String label, double min, double max, double step, double value, Consumer<Double> onChange,
               int x, int y, int w) {
            this.label = label;
            this.min = min;
            this.max = max;
            this.step = step;
            this.onChange = onChange;
            this.x = x;
            this.y = y;
            this.w = w;
            this.value = clamp(value, min, max);
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        private double clamp(double v, double lo, double hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private void setFromMouse(double mouseX) {
            double frac = clamp((mouseX - x - 6) / (w - 12), 0, 1);
            double v = min + frac * (max - min);
            if (step > 0) {
                v = Math.round(v / step) * step;
            }
            v = clamp(v, min, max);
            if (v != value) {
                value = v;
                onChange.accept(v);
            }
        }

        private int handleX() {
            return x + 6 + (int) Math.round((value - min) / (max - min) * (w - 12)) - 3;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
            graphics.text(font, label, x, y, enabled ? 0xFFB8C7D9 : 0xFF5A6B80);
            String shown = step >= 1 ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
            graphics.text(font, shown, x + w - font.width(shown), y, 0xFF9FB2C9);
            int ty = y + 13;
            graphics.fill(x, ty, x + w, ty + 4, 0xFF0A1424);
            graphics.fill(x, ty, x + w, ty + 1, 0xFF2A3F66);
            graphics.fill(x, ty + 3, x + w, ty + 4, 0xFF2A3F66);
            int hx = handleX();
            graphics.fill(hx, ty - 2, hx + 6, ty + 6, dragging ? 0xFFFFE066 : 0xFF4A7CF7);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (enabled && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 18) {
                dragging = true;
                setFromMouse(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public void mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (dragging) {
                setFromMouse(mouseX);
            }
        }

        @Override
        public void mouseReleased(double mouseX, double mouseY, int button) {
            dragging = false;
        }
    }
}
