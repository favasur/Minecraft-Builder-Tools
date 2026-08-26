package net.neoforged.neoforge.client.event;

import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class RenderGuiEvent {
    private RenderGuiEvent() {
    }

    public static final class Post {
        private final GuiGraphicsExtractor guiGraphics;

        public Post(GuiGraphicsExtractor guiGraphics) {
            this.guiGraphics = guiGraphics;
        }

        public GuiGraphicsExtractor getGuiGraphics() {
            return guiGraphics;
        }
    }
}
