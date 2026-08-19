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
        // Keep the bounding box glued to the rotated visual: whenever the block or its rotation
        // changes (server-side and after syncing to clients), re-center the entity on the visual's
        // collision box and re-measure it, so the hitbox always matches what the player sees.
        if (DATA_BLOCK_STATE.equals(key) || DATA_YAW.equals(key) || DATA_PITCH.equals(key)) {
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
     * placement yaw/pitch around the cell center, mapped back onto the cell. Axis-aligned blocks
     * keep the exact shape bounds of the cell.
     */
    public AABB visualCollisionBox() {
        BlockState state = getRepresentedState();
        AABB shape = this.level() != null
                ? state.getCollisionShape(this.level(), BlockPos.ZERO).bounds()
                : new AABB(0, 0, 0, 1, 1, 1);
        float yaw = getPlacementYaw();
        float pitch = getPlacementPitch();
        if (yaw == 0.0f && pitch == 0.0f) {
            BlockPos c = cell();
            return new AABB(c.getX() + shape.minX, c.getY() + shape.minY, c.getZ() + shape.minZ,
                    c.getX() + shape.maxX, c.getY() + shape.maxY, c.getZ() + shape.maxZ);
        }
        org.joml.Quaternionf rot = net.buildertools.util.OffGridTransform.rotation(yaw, pitch);
        double minX = 9, minY = 9, minZ = 9, maxX = -9, maxY = -9, maxZ = -9;
        for (int i = 0; i < 8; i++) {
            float px = (i & 1) == 0 ? (float) shape.minX : (float) shape.maxX;
            float py = (i & 2) == 0 ? (float) shape.minY : (float) shape.maxY;
            float pz = (i & 4) == 0 ? (float) shape.minZ : (float) shape.maxZ;
            org.joml.Vector3f p = new org.joml.Vector3f(px - 0.5f, py - 0.5f, pz - 0.5f);
            rot.transform(p);
            p.add(0.5f, 0.5f, 0.5f);
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }
        BlockPos c = cell();
        return new AABB(c.getX() + minX, c.getY() + minY, c.getZ() + minZ,
                c.getX() + maxX, c.getY() + maxY, c.getZ() + maxZ);
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
    public boolean canBeCollidedWith() {
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
