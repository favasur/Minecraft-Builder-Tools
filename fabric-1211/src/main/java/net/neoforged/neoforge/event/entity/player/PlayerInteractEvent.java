package net.neoforged.neoforge.event.entity.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

public final class PlayerInteractEvent {
    private PlayerInteractEvent() {
    }

    public abstract static class Cancellable {
        private boolean canceled;

        public void setCanceled(boolean canceled) {
            this.canceled = canceled;
        }

        public boolean isCanceled() {
            return canceled;
        }
    }

    public static final class LeftClickBlock extends Cancellable {
        private final Player entity;
        private final Level level;
        private final BlockPos pos;
        private final BlockHitResult hitVec;

        public LeftClickBlock(Player entity, Level level, BlockPos pos, BlockHitResult hitVec) {
            this.entity = entity;
            this.level = level;
            this.pos = pos;
            this.hitVec = hitVec;
        }

        public Player getEntity() { return entity; }
        public Level getLevel() { return level; }
        public BlockPos getPos() { return pos; }
        public BlockHitResult getHitVec() { return hitVec; }
        public Direction getFace() { return hitVec.getDirection(); }
    }

    public static final class RightClickBlock extends Cancellable {
        private final Player entity;
        private final Level level;
        private final BlockPos pos;
        private final BlockHitResult hitVec;

        public RightClickBlock(Player entity, Level level, BlockPos pos, BlockHitResult hitVec) {
            this.entity = entity;
            this.level = level;
            this.pos = pos;
            this.hitVec = hitVec;
        }

        public Player getEntity() { return entity; }
        public Level getLevel() { return level; }
        public BlockPos getPos() { return pos; }
        public BlockHitResult getHitVec() { return hitVec; }
        public Direction getFace() { return hitVec.getDirection(); }
    }

    public static final class EntityInteract extends Cancellable {
        private final Player entity;
        private final Level level;
        private final Entity target;

        public EntityInteract(Player entity, Level level, Entity target) {
            this.entity = entity;
            this.level = level;
            this.target = target;
        }

        public Player getEntity() { return entity; }
        public Level getLevel() { return level; }
        public Entity getTarget() { return target; }
    }
}
