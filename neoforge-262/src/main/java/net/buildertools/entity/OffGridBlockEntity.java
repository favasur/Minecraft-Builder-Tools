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
import net.minecraft.world.phys.Vec3;
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
    private static final EntityDataAccessor<Boolean> DATA_BILLBOARD = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.BOOLEAN);
    /** The world-space center of the block model (rotation pivot), which can be fractional so
     *  flush-adjacent blocks form rotated strata. Everything else (collision box, visual anchor)
     *  is derived from this center, so a block placed diagonally still collides exactly where its
     *  rotated model renders. */
    private static final EntityDataAccessor<Float> DATA_CX = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_CY = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_CZ = SynchedEntityData.defineId(OffGridBlockEntity.class, EntityDataSerializers.FLOAT);

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
        builder.define(DATA_BILLBOARD, false);
        builder.define(DATA_CX, 0.0f);
        builder.define(DATA_CY, 0.0f);
        builder.define(DATA_CZ, 0.0f);
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

    /** True when the block is a billboard that always faces the player. */
    public boolean isBillboard() {
        return this.entityData.get(DATA_BILLBOARD);
    }

    public void setBillboard(boolean billboard) {
        this.entityData.set(DATA_BILLBOARD, billboard);
    }

    @Nullable
    public UUID getDisplayUuid() {
        return this.displayUuid;
    }

    public void setDisplayUuid(UUID uuid) {
        this.displayUuid = uuid;
    }

    /** The world-space model center (rotation pivot). */
    public Vec3 modelCenter() {
        return new Vec3(this.entityData.get(DATA_CX), this.entityData.get(DATA_CY), this.entityData.get(DATA_CZ));
    }

    public void setModelCenter(double x, double y, double z) {
        this.entityData.set(DATA_CX, (float) x);
        this.entityData.set(DATA_CY, (float) y);
        this.entityData.set(DATA_CZ, (float) z);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        // Keep the bounding box glued to the rotated visual: whenever the block or its rotation
        // changes (server-side and after syncing to clients), re-center the entity on the visual's
        // collision box and re-measure it, so the hitbox always matches what the player sees.
        if (DATA_BLOCK_STATE.equals(key) || DATA_YAW.equals(key) || DATA_PITCH.equals(key)
                || DATA_CX.equals(key) || DATA_CY.equals(key) || DATA_CZ.equals(key)) {
            if (this.level() != null) {
                // getBoundingBox() is final and is built by makeBoundingBox(), which is bottom-
                // anchored in Y and centered in X/Z on the entity position. So the entity must sit
                // at the BOTTOM-CENTER of the visual box (not its center) or the collision box
                // floats up by half its height and never matches the rotated model.
                Vec3 anchor = visualAnchor();
                this.setPos(anchor.x, anchor.y, anchor.z);
                this.refreshDimensions();
            }
        }
    }

    /**
     * Collision box matches the rotated model, not the raw cell: the block shape is rotated by the
     * placement yaw/pitch around the cell center (exactly like the rendered model), so a diagonal
     * block collides along its true walls - no invisible bumps where the axis-aligned cell would
     * stick out past the visual.
     */
    @Override
    public EntityDimensions getDimensions(Pose pose) {
        AABB box = visualCollisionBox();
        float w = (float) Math.max(box.getXsize(), box.getZsize());
        float h = (float) Math.max(box.getYsize(), 0.05f);
        return EntityDimensions.fixed(w, h);
    }

    /**
     * The world-space collision box of the rotated model: the block's shape bounds rotated by the
     * placement yaw/pitch around the model center, mapped back onto the world. Because the box is
     * derived from the same center and rotation as the rendered display, a diagonal block collides
     * exactly where its rotated model renders - no invisible bumps, and flush-adjacent strata stay
     * flush.
     */
    public AABB visualCollisionBox() {
        BlockState state = getRepresentedState();
        AABB shape = this.level() != null
                ? state.getCollisionShape(this.level(), BlockPos.ZERO).bounds()
                : new AABB(0, 0, 0, 1, 1, 1);
        Vec3 c = modelCenter();
        return net.buildertools.util.OffGridTransform.boxAround(
                c.x, c.y, c.z, getPlacementYaw(), getPlacementPitch(), shape);
    }

    /**
     * The world position the entity must sit at so makeBoundingBox() equals the visual box:
     * the bottom-center of the visual footprint (X/Z centered on the box, Y at its base).
     */
    public Vec3 visualAnchor() {
        AABB box = visualCollisionBox();
        return new Vec3(box.getCenter().x, box.minY, box.getCenter().z);
    }

    @Override
    public boolean canBeCollidedWith(@Nullable Entity other) {
        return this.isSolidCollidable();
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    /** Middle-click (pick block) gives the represented block back, like any normal block. */
    @Override
    public ItemStack getPickResult() {
        BlockState state = getRepresentedState();
        return state.isAir() ? null : new ItemStack(state.getBlock());
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
        // Blocks saved before the center was stored: derive it from the entity position,
        // which was the bottom-center of the cell box (X/Z at cell center, Y at the base).
        double cx = input.read("cx", com.mojang.serialization.Codec.DOUBLE).orElseGet(() -> this.getX());
        double cy = input.read("cy", com.mojang.serialization.Codec.DOUBLE).orElseGet(() -> this.getY() + 0.5);
        double cz = input.read("cz", com.mojang.serialization.Codec.DOUBLE).orElseGet(() -> this.getZ());
        this.setModelCenter(cx, cy, cz);
        input.getString("display").ifPresent(s -> {
            try {
                this.setDisplayUuid(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        });
        this.setSolidCollidable(input.getBooleanOr("collidable", true));
        this.setBillboard(input.getBooleanOr("billboard", false));
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.store("block_state", BlockState.CODEC, this.getRepresentedState());
        output.putFloat("yaw", this.getPlacementYaw());
        output.putFloat("pitch", this.getPlacementPitch());
        Vec3 c = this.modelCenter();
        output.putDouble("cx", c.x);
        output.putDouble("cy", c.y);
        output.putDouble("cz", c.z);
        if (this.displayUuid != null) {
            output.putString("display", this.displayUuid.toString());
        }
        output.putBoolean("collidable", this.isSolidCollidable());
        output.putBoolean("billboard", this.isBillboard());
    }

    /** The grid cell containing the model center (used for legacy cell matching). */
    public BlockPos cell() {
        return BlockPos.containing(modelCenter());
    }
}
