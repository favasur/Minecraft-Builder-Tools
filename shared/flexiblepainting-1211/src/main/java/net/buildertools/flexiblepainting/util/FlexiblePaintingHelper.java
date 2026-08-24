package net.buildertools.flexiblepainting.util;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.decoration.Painting;

public final class FlexiblePaintingHelper {
    public static final String SURFACE_TYPE_KEY = "buildertools_flexible_painting_surface";
    public static final String ROTATION_YAW_KEY = "buildertools_flexible_painting_yaw";
    public static final String ROTATION_PITCH_KEY = "buildertools_flexible_painting_pitch";
    private static final EntityDataAccessor<Integer> SURFACE_TYPE =
            SynchedEntityData.defineId(Painting.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> ROTATION_YAW =
            SynchedEntityData.defineId(Painting.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> ROTATION_PITCH =
            SynchedEntityData.defineId(Painting.class, EntityDataSerializers.FLOAT);
    private static final ThreadLocal<SurfaceType> CREATING_SURFACE = new ThreadLocal<>();

    private FlexiblePaintingHelper() {
    }

    public static EntityDataAccessor<Integer> surfaceTypeAccessor() {
        return SURFACE_TYPE;
    }

    public static EntityDataAccessor<Float> rotationYawAccessor() {
        return ROTATION_YAW;
    }

    public static EntityDataAccessor<Float> rotationPitchAccessor() {
        return ROTATION_PITCH;
    }

    public static void defineSurfaceType(SynchedEntityData.Builder builder) {
        builder.define(SURFACE_TYPE, SurfaceType.WALL.id());
        builder.define(ROTATION_YAW, 0.0f);
        builder.define(ROTATION_PITCH, 0.0f);
    }

    public static SurfaceType getSurfaceType(Painting painting) {
        SurfaceType creating = CREATING_SURFACE.get();
        if (creating != null) {
            return creating;
        }
        if (painting instanceof FlexiblePaintingAccess access) {
            return access.flexiblePainting$getSurfaceType();
        }
        return SurfaceType.WALL;
    }

    public static void setSurfaceType(Painting painting, SurfaceType type) {
        if (painting instanceof FlexiblePaintingAccess access && type != null) {
            access.flexiblePainting$setSurfaceType(type);
        }
    }

    public static void beginCreating(SurfaceType type) {
        CREATING_SURFACE.set(type);
    }

    public static void endCreating() {
        CREATING_SURFACE.remove();
    }

    public static SurfaceType creatingSurface() {
        return CREATING_SURFACE.get();
    }

    public static void saveSurfaceType(net.minecraft.nbt.CompoundTag tag, Painting painting) {
        tag.putByte(SURFACE_TYPE_KEY, (byte) getSurfaceType(painting).id());
        saveRotation(tag, painting);
    }

    public static void loadSurfaceType(net.minecraft.nbt.CompoundTag tag, Painting painting) {
        if (tag.contains(SURFACE_TYPE_KEY)) {
            setSurfaceType(painting, SurfaceType.fromId(tag.getByte(SURFACE_TYPE_KEY)));
        }
        if (tag.contains(ROTATION_YAW_KEY) || tag.contains(ROTATION_PITCH_KEY)) {
            setRotation(painting, tag.getFloat(ROTATION_YAW_KEY), tag.getFloat(ROTATION_PITCH_KEY));
        }
    }

    public static float getRotationYaw(Painting painting) {
        return painting instanceof FlexiblePaintingAccess access ? access.flexiblePainting$getRotationYaw() : 0.0f;
    }

    public static float getRotationPitch(Painting painting) {
        return painting instanceof FlexiblePaintingAccess access ? access.flexiblePainting$getRotationPitch() : 0.0f;
    }

    public static void setRotation(Painting painting, float yaw, float pitch) {
        if (painting instanceof FlexiblePaintingAccess access) {
            access.flexiblePainting$setRotation(yaw, pitch);
        }
    }

    public static void saveRotation(net.minecraft.nbt.CompoundTag tag, Painting painting) {
        float yaw = getRotationYaw(painting);
        float pitch = getRotationPitch(painting);
        if (yaw != 0.0f) tag.putFloat(ROTATION_YAW_KEY, yaw);
        if (pitch != 0.0f) tag.putFloat(ROTATION_PITCH_KEY, pitch);
    }
}
