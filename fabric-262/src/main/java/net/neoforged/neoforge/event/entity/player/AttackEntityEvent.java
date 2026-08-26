package net.neoforged.neoforge.event.entity.player;

import net.minecraft.world.entity.player.Player;

public final class AttackEntityEvent {
    private final Player entity;
    private boolean canceled;

    public AttackEntityEvent(Player entity) {
        this.entity = entity;
    }

    public Player getEntity() {
        return entity;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }

    public boolean isCanceled() {
        return canceled;
    }
}
