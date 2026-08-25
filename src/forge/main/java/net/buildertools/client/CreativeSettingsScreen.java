package net.buildertools.client;

import net.buildertools.client.settings.BuilderSettings;
import net.buildertools.network.packet.PlayerAbilitiesPacket;
import net.buildertools.network.packet.WorldSettingsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Creative settings window, built onto the vanilla creative inventory: the full
 * {@link CreativeModeInventoryScreen} (tabs, search, item picker) stays exactly as vanilla, and a
 * dark-blue settings panel takes the empty space to its right (~1/4 of the screen width). The
 * panel is scrollable when its content is taller than the window.
 */
@OnlyIn(Dist.CLIENT)
public final class CreativeSettingsScreen extends CreativeModeInventoryScreen {
    private static final int INVENTORY_WIDTH = 195;
    private static final int CONTENT_TOP = 30; // below the panel title/separator

    private final Player player;
    private final List<Control> controls = new ArrayList<>();

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private int contentH;
    private int maxScroll;
    private int scrollOffset;

    private boolean pauseTime;

    public CreativeSettingsScreen(LocalPlayer player) {
        super(player, Minecraft.getInstance().level.enabledFeatures(),
                Minecraft.getInstance().options.operatorItemsTab().get());
        this.player = player;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        // Vanilla creative inventory setup: tabs, search box, item picker grid, and the vanilla
        // centered window position. The settings panel only ever sits to the right of it.
        super.init();

        controls.clear();
        panelX = leftPos + INVENTORY_WIDTH + 12;
        panelY = 12;
        int available = this.width - panelX - 12;
        panelW = Math.max(140, Math.min(this.width / 4, available));

        scrollOffset = 0;
        pauseTime = false;

        int x = panelX + 12;
        int w = panelW - 24;
        int cy = panelY + 32;

        cy = section("World", cy);
        controls.add(new Slider("Time of Day", 0, 24000, 50,
                this.player.level().getDayTime() % 24000,
                v -> ClientPackets.sendToServer(new WorldSettingsPacket(Math.round(v), null, WorldSettingsPacket.SKIP_WEATHER)),
                x, cy, w));
        cy += 28;
        controls.add(new Toggle("Pause Time", () -> pauseTime, v -> {
            pauseTime = v;
            ClientPackets.sendToServer(new WorldSettingsPacket(WorldSettingsPacket.SKIP_TIME, v, WorldSettingsPacket.SKIP_WEATHER));
        }, x, cy, w));
        cy += 28;
        int bw = (w - 8) / 3;
        controls.add(new Button("Clear", () -> sendWeather(0), x, cy, bw, 16));
        controls.add(new Button("Rain", () -> sendWeather(1), x + bw + 4, cy, bw, 16));
        controls.add(new Button("Thunder", () -> sendWeather(2), x + 2 * (bw + 4), cy, bw, 16));
        cy += 28;

        cy = section("Player", cy);
        controls.add(new Slider("Flight Speed", 0.05, 2.0, 0.05,
                this.player.getAbilities().getFlyingSpeed(),
                v -> ClientPackets.sendToServer(new PlayerAbilitiesPacket(v.floatValue(), null, null)),
                x, cy, w));
        cy += 28;
        controls.add(new Toggle("No Clip", BuilderSettings::isNoClip, v -> {
            BuilderSettings.setNoClip(v);
            this.player.noPhysics = v;
            ClientPackets.sendToServer(new PlayerAbilitiesPacket(PlayerAbilitiesPacket.SKIP_SPEED, v, null));
        }, x, cy, w));
        cy += 28;

        cy = section("Brushes", cy);
        controls.add(new Toggle("Air Placement", BuilderSettings::isAirPlacement, v -> {
            BuilderSettings.setAirPlacement(v);
            if (v) {
                this.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "Air placement on - scroll wheel changes paint distance"),
                        true);
            }
        }, x, cy, w));
        cy += 28;
        controls.add(new Slider("Tool Reach", 5, 64, 1,
                BuilderSettings.getToolReach(),
                v -> BuilderSettings.setToolReach(v.floatValue()),
                x, cy, w));
        cy += 28;
        controls.add(new Slider("Brush Opacity", 0.05, 1.0, 0.05,
                BuilderSettings.getBrushOpacity(),
                v -> BuilderSettings.setBrushOpacity(v.floatValue()),
                x, cy, w));
        cy += 28;

        cy = section("Rendering", cy);
        controls.add(new Toggle("Fullbright", BuilderSettings::isFullbright, BuilderSettings::setFullbright, x, cy, w));
        cy += 28;
        controls.add(new Slider("Selection Opacity", 0.05, 0.6, 0.01,
                BuilderSettings.getSelectionOpacity(),
                v -> BuilderSettings.setSelectionOpacity(v.floatValue()),
                x, cy, w));
        cy += 28;
        controls.add(new Slider("Panel Opacity", 0.05, 1.0, 0.05,
                BuilderSettings.getSelectionPanelOpacity(),
                v -> BuilderSettings.setSelectionPanelOpacity(v.floatValue()),
                x, cy, w));
        cy += 28;
        controls.add(new Toggle("Display Legend", BuilderSettings::isDisplayLegend, BuilderSettings::setDisplayLegend, x, cy, w));
        cy += 28;

        cy = section("Entity Tool", cy);
        controls.add(new Toggle("Lock to Surface", BuilderSettings::isSurfaceLock, BuilderSettings::setSurfaceLock, x, cy, w));
        cy += 28;
        controls.add(new Toggle("Grid Snap", BuilderSettings::isGridSnap, BuilderSettings::setGridSnap, x, cy, w));
        cy += 28;
        controls.add(new Slider("Grid Size", 0.1, 2.0, 0.1,
                BuilderSettings.getGridSize(),
                v -> BuilderSettings.setGridSize(v.floatValue()),
                x, cy, w));
        cy += 12;

        // The panel wraps its content, scrolling when it is taller than the window.
        contentH = cy + 4 - panelY;
        panelH = Math.min(contentH, this.height - 24);
        maxScroll = Math.max(0, contentH - panelH);
    }

    private void sendWeather(int weather) {
        ClientPackets.sendToServer(new WorldSettingsPacket(WorldSettingsPacket.SKIP_TIME, null, weather));
    }

    /** Draws a section header and returns the y for the first control below it. */
    private int section(String name, int cy) {
        controls.add(new Header(name, panelX + 12, cy, panelW - 24));
        return cy + 22;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Vanilla creative inventory (tabs, item grid, search) renders first, then the panel.
        super.render(graphics, mouseX, mouseY, partialTick);

        // Settings panel background and title.
        graphics.fill(panelX - 4, panelY - 4, panelX + panelW + 4, panelY + panelH + 4, 0xEE070C16);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0122038);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 2, 0xFF3A5A8C);
        graphics.fill(panelX, panelY + panelH - 2, panelX + panelW, panelY + panelH, 0xFF3A5A8C);
        graphics.fill(panelX, panelY, panelX + 2, panelY + panelH, 0xFF3A5A8C);
        graphics.fill(panelX + panelW - 2, panelY, panelX + panelW, panelY + panelH, 0xFF3A5A8C);
        graphics.drawString(this.font, "Creative Settings", panelX + 12, panelY + 10, 0xFFFFFFFF);
        graphics.fill(panelX + 8, panelY + 26, panelX + panelW - 8, panelY + 27, 0xFF2A3F66);

        // Controls, clipped to the panel and translated by the scroll offset.
        graphics.enableScissor(panelX, panelY + CONTENT_TOP, panelX + panelW, panelY + panelH);
        graphics.pose().pushPose();
        graphics.pose().translate(0, -scrollOffset, 0);
        for (Control control : controls) {
            control.render(graphics, mouseX, mouseY);
        }
        graphics.pose().popPose();
        graphics.disableScissor();

        // Scrollbar (only when the content overflows).
        if (maxScroll > 0) {
            int trackTop = panelY + CONTENT_TOP;
            int trackH = panelH - CONTENT_TOP;
            int thumbH = Math.max(20, trackH * trackH / contentH);
            int thumbY = trackTop + (trackH - thumbH) * scrollOffset / maxScroll;
            int sx = panelX + panelW - 5;
            graphics.fill(sx, trackTop, sx + 3, trackTop + trackH, 0xFF0A1424);
            graphics.fill(sx, thumbY, sx + 3, thumbY + thumbH, 0xFF4A7CF7);
        }
    }

    // ------------------------------------------------------------------
    // Input dispatch (panel first, then the vanilla creative inventory)
    // ------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {
            if (maxScroll > 0) {
                scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) Math.round(deltaY) * 10));
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {
            double adjustedY = mouseY + scrollOffset;
            for (Control control : controls) {
                if (control.mouseClicked(mouseX, adjustedY, button)) {
                    return true;
                }
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        for (Control control : controls) {
            control.mouseDragged(mouseX, mouseY + scrollOffset, button, dragX, dragY);
        }
        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        for (Control control : controls) {
            control.mouseReleased(mouseX, mouseY + scrollOffset, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    private interface Control {
        void render(GuiGraphics graphics, int mouseX, int mouseY);

        default boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }

        default void mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        }

        default void mouseReleased(double mouseX, double mouseY, int button) {
        }
    }

    private final class Header implements Control {
        private final String text;
        private final int x;
        private final int y;
        private final int w;

        Header(String text, int x, int y, int w) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.w = w;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.drawString(font, text, x, y, 0xFF7FA8E0);
            graphics.fill(x, y + 11, x + w, y + 12, 0xFF223350);
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
        public void render(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.drawString(font, label, x, y, 0xFFB8C7D9);
            String shown = step >= 1 ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
            graphics.drawString(font, shown, x + w - font.width(shown), y, 0xFF9FB2C9);
            // Track
            int ty = y + 13;
            graphics.fill(x, ty, x + w, ty + 4, 0xFF0A1424);
            graphics.fill(x, ty, x + w, ty + 1, 0xFF2A3F66);
            graphics.fill(x, ty + 3, x + w, ty + 4, 0xFF2A3F66);
            // Handle
            int hx = handleX();
            graphics.fill(hx, ty - 2, hx + 6, ty + 6, dragging ? 0xFFFFE066 : 0xFF4A7CF7);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 18) {
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

    private final class Toggle implements Control {
        private final String label;
        private final java.util.function.BooleanSupplier getter;
        private final Consumer<Boolean> onChange;
        private final int x;
        private final int y;
        private final int w;

        Toggle(String label, java.util.function.BooleanSupplier getter, Consumer<Boolean> onChange, int x, int y, int w) {
            this.label = label;
            this.getter = getter;
            this.onChange = onChange;
            this.x = x;
            this.y = y;
            this.w = w;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY) {
            graphics.drawString(font, label, x, y + 3, 0xFFB8C7D9);
            boolean on = getter.getAsBoolean();
            int bx = x + w - 20;
            graphics.fill(bx, y, bx + 20, y + 14, on ? 0xFF2E6BE6 : 0xFF0A1424);
            graphics.fill(bx, y, bx + 20, y + 1, 0xFF3A5A8C);
            graphics.fill(bx, y + 13, bx + 20, y + 14, 0xFF3A5A8C);
            graphics.fill(bx, y, bx + 1, y + 14, 0xFF3A5A8C);
            graphics.fill(bx + 19, y, bx + 20, y + 14, 0xFF3A5A8C);
            graphics.drawString(font, on ? "ON" : "OFF", bx + (20 - font.width(on ? "ON" : "OFF")) / 2, y + 2, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 14) {
                onChange.accept(!getter.getAsBoolean());
                return true;
            }
            return false;
        }
    }

    private final class Button implements Control {
        private final String label;
        private final Runnable onPress;
        private final int x;
        private final int y;
        private final int w;
        private final int h;

        Button(String label, Runnable onPress, int x, int y, int w, int h) {
            this.label = label;
            this.onPress = onPress;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY) {
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
            graphics.fill(x, y, x + w, y + h, 0xFF0A1424);
            graphics.fill(x, y, x + w, y + 1, hovered ? 0xFF4A7CF7 : 0xFF3A5A8C);
            graphics.fill(x, y + h - 1, x + w, y + h, 0xFF3A5A8C);
            graphics.fill(x, y, x + 1, y + h, 0xFF3A5A8C);
            graphics.fill(x + w - 1, y, x + w, y + h, 0xFF3A5A8C);
            int tx = x + (w - font.width(label)) / 2;
            graphics.drawString(font, label, tx, y + (h - 8) / 2, hovered ? 0xFFFFFFFF : 0xFFB8C7D9);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) {
                onPress.run();
                return true;
            }
            return false;
        }
    }
}
