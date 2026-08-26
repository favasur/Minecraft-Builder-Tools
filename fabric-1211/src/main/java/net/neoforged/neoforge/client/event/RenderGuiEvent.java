package net.neoforged.neoforge.client.event;

import net.minecraft.client.gui.GuiGraphics;

public final class RenderGuiEvent {
    private RenderGuiEvent() {
    }

    public static final class Post {
        private final GuiGraphics guiGraphics;

        public Post(GuiGraphics guiGraphics) {
            this.guiGraphics = guiGraphics;
        }

        public GuiGraphics getGuiGraphics() {
            return guiGraphics;
        }
    }
}
