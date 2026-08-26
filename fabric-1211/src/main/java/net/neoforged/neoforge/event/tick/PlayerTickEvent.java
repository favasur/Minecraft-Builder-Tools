package net.neoforged.neoforge.event.tick;

import net.minecraft.world.entity.player.Player;

public final class PlayerTickEvent {
    private PlayerTickEvent() {
    }

    public static final class Post {
        private final Player entity;

        public Post(Player entity) {
            this.entity = entity;
        }

        public Player getEntity() {
            return entity;
        }
    }
}
