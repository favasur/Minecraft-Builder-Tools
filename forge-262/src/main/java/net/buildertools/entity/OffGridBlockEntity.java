package net.buildertools.entity;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The solid "off-grid" block, mirroring the original offgridblocks mod: a small solid entity
 * occupies the cell and provides the collision box, while a
 * {@link net.minecraft.world.entity.Display.BlockDisplay} (linked by {@link #getDisplayUuid()})
 * renders the rotated model. The rotation (yaw/pitch) is stored here and comes from the mod's
 * placement-preview rotation interface - not from the player's facing.
 *
 * <p>26.2 note: unlike 1.21.1 there is no {@code OPTIONAL_UUID} entity-data serializer, and the
 * display link is only ever read server-side (to discard the pair), so the UUID is kept as a
 * plain field saved to NBT instead of synced entity data.</p>
 */
public class OffGridBlockEntity extends Entity {
    private static final EntityDataAccessor<BlockState> DATA_BLOCK_STATE = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.BLOCK_STATE);
    private static final EntityDataAccessor<Float> DATA_YAW = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_PITCH = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_COLLIDABLE = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.BOOLEAN);

    /** UUID of the linked {@code BlockDisplay} that renders the rotated model (server-only). */
    private UUID displayUuid;

    public OffGridBlockEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_BLOCK_STATE, Blocks.AIR.defaultBlockState());
        builder.define(DATA_YAW, 0.0f);
        builder.define(DATA_PITCH, 0.0f);
        builder.define(DATA_COLLIDABLE, true);
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

    @Nullable
    public UUID getDisplayUuid() {
        return this.displayUuid;
    }

    public void setDisplayUuid(UUID uuid) {
        this.displayUuid = uuid;
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
    public boolean canBeCollidedWith(@Nullable Entity other) {
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
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
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
        if (this.level() instanceof ServerLevel serverLevel && this.displayUuid != null) {
            Entity display = serverLevel.getEntity(this.displayUuid);
            if (display != null) {
                display.discard();
            }
        }
        this.discard();
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        input.read("block_state", BlockState.CODEC).ifPresent(this::setRepresentedState);
        this.setPlacementRotation(input.getFloatOr("yaw", 0.0f), input.getFloatOr("pitch", 0.0f));
        input.getString("display").ifPresent(s -> {
            try {
                this.setDisplayUuid(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        });
        this.setSolidCollidable(input.getBooleanOr("collidable", true));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.store("block_state", BlockState.CODEC, this.getRepresentedState());
        output.putFloat("yaw", this.getPlacementYaw());
        output.putFloat("pitch", this.getPlacementPitch());
        if (this.displayUuid != null) {
            output.putString("display", this.displayUuid.toString());
        }
        output.putBoolean("collidable", this.isSolidCollidable());
    }

    /** Convenience: the cell this solid block occupies. */
    public BlockPos cell() {
        return new BlockPos(this.getBlockX(), this.getBlockY(), this.getBlockZ());
    }
}
