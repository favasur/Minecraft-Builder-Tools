package net.buildertools.entity;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * The solid "off-grid" block, mirroring the original offgridblocks mod: a small solid entity
 * occupies the cell and provides the collision box, while a
 * {@link net.minecraft.world.entity.Display.BlockDisplay} (linked by {@link #getDisplayUuid()})
 * renders the rotated model. The rotation (yaw/pitch) is stored here and comes from the mod's
 * placement-preview rotation interface - not from the player's facing.
 */
public class OffGridBlockEntity extends Entity {
    private static final EntityDataAccessor<BlockState> DATA_BLOCK_STATE = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Float> DATA_YAW = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_PITCH = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_COLLIDABLE = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<UUID>> DATA_DISPLAY = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    public OffGridBlockEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_BLOCK_STATE, Blocks.AIR.defaultBlockState());
        builder.define(DATA_YAW, 0.0f);
        builder.define(DATA_PITCH, 0.0f);
        builder.define(DATA_COLLIDABLE, true);
        builder.define(DATA_DISPLAY, Optional.empty());
    }

    public BlockState getRepresentedState() {
        return this.entityData.get(DATA_BLOCK_STATE);
    }

    public void setRepresentedState(BlockState state) {
        this.entityData.set(DATA_BLOCK_STATE, state);
    }

    public float getPlacementYaw() {
        return this.entityData.get(DATA_YAW);
    }

    public float getPlacementPitch() {
        return this.entityData.get(DATA_PITCH);
    }

    public void setPlacementRotation(float yaw, float pitch) {
        this.entityData.set(DATA_YAW, yaw);
        this.entityData.set(DATA_PITCH, pitch);
    }

    public boolean isSolidCollidable() {
        return this.entityData.get(DATA_COLLIDABLE);
    }

    public void setSolidCollidable(boolean collidable) {
        this.entityData.set(DATA_COLLIDABLE, collidable);
    }

    public Optional<UUID> getDisplayUuid() {
        return this.entityData.get(DATA_DISPLAY);
    }

    public void setDisplayUuid(UUID uuid) {
        this.entityData.set(DATA_DISPLAY, Optional.ofNullable(uuid));
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_BLOCK_STATE.equals(key)) {
            this.refreshDimensions();
        }
    }

    /**
     * Collision box matches the represented block's shape within the cell: full width/depth and
     * the block's height from the cell floor, so slabs and stairs collide like they render.
     */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        BlockState state = getRepresentedState();
        AABB box = state.getCollisionShape(this.level(), BlockPos.ZERO).bounds();
        float w = (float) Math.max(box.getXsize(), box.getZsize());
        float h = (float) Math.max(box.getYsize(), 0.05f);
        return EntityDimensions.fixed(w, h);
    }

    @Override
    public boolean canBeCollidedWith() {
        return this.isSolidCollidable();
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }

    /** Breaking the block: drop the item and remove the pair (this entity + the display child). */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide()) {
            return true;
        }
        BlockState state = getRepresentedState();
        boolean creative = source.getEntity() instanceof Player player && player.getAbilities().instabuild;
        if (!creative && !state.isAir()) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0f, 0.9f);
            this.level().addFreshEntity(new ItemEntity(this.level(),
                    this.getX(), this.getY() + 0.25, this.getZ(),
                    new ItemStack(state.getBlock())));
        }
        discardWithDisplay();
        return true;
    }

    /** Removes the linked display child and this entity. */
    public void discardWithDisplay() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.getDisplayUuid().ifPresent(uuid -> {
                Entity display = serverLevel.getEntity(uuid);
                if (display != null) {
                    display.discard();
                }
            });
        }
        this.discard();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.setRepresentedState(net.minecraft.nbt.NbtUtils.readBlockState(
                this.level().holderLookup(Registries.BLOCK), tag.getCompound("block_state")));
        this.setPlacementRotation(tag.getFloat("yaw"), tag.getFloat("pitch"));
        if (tag.hasUUID("display")) {
            this.setDisplayUuid(tag.getUUID("display"));
        }
        if (tag.contains("collidable")) {
            this.setSolidCollidable(tag.getBoolean("collidable"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.put("block_state", net.minecraft.nbt.NbtUtils.writeBlockState(this.getRepresentedState()));
        tag.putFloat("yaw", this.getPlacementYaw());
        tag.putFloat("pitch", this.getPlacementPitch());
        this.getDisplayUuid().ifPresent(uuid -> tag.putUUID("display", uuid));
        tag.putBoolean("collidable", this.isSolidCollidable());
    }

    /** Convenience: the cell this solid block occupies. */
    public BlockPos cell() {
        return new BlockPos(this.getBlockX(), this.getBlockY(), this.getBlockZ());
    }
}
