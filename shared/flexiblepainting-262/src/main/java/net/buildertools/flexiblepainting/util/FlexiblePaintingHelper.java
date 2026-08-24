package net.buildertools.flexiblepainting.util;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public final class FlexiblePaintingHelper {
    public static final String SURFACE_TYPE_KEY = "buildertools_flexible_painting_surface";
    private static final EntityDataAccessor<Integer> SURFACE_TYPE =
            SynchedEntityData.defineId(Painting.class, EntityDataSerializers.INT);
    private static final ThreadLocal<SurfaceType> CREATING_SURFACE = new ThreadLocal<>();

    private FlexiblePaintingHelper() { }
    public static EntityDataAccessor<Integer> surfaceTypeAccessor() { return SURFACE_TYPE; }
    public static void defineSurfaceType(SynchedEntityData.Builder builder) { builder.define(SURFACE_TYPE, 0); }
    public static SurfaceType getSurfaceType(Painting painting) {
        SurfaceType creating = CREATING_SURFACE.get();
        if (creating != null) return creating;
        if (painting instanceof FlexiblePaintingAccess access) return access.flexiblePainting$getSurfaceType();
        return SurfaceType.WALL;
    }
    public static void setSurfaceType(Painting painting, SurfaceType type) {
        if (painting instanceof FlexiblePaintingAccess access && type != null) access.flexiblePainting$setSurfaceType(type);
    }
    public static void beginCreating(SurfaceType type) { CREATING_SURFACE.set(type); }
    public static void endCreating() { CREATING_SURFACE.remove(); }
    public static void saveSurfaceType(ValueOutput output, Painting painting) { output.putByte(SURFACE_TYPE_KEY, (byte) getSurfaceType(painting).id()); }
    public static void loadSurfaceType(ValueInput input, Painting painting) { setSurfaceType(painting, SurfaceType.fromId(input.getByteOr(SURFACE_TYPE_KEY, (byte) 0))); }
}
