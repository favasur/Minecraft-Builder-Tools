package net.buildertools.flexiblepainting.api;

public interface FlexiblePaintingAccess {
    SurfaceType flexiblePainting$getSurfaceType();

    void flexiblePainting$setSurfaceType(SurfaceType type);

    /** Builder Tools' optional arbitrary visual rotation, in degrees around the painting pivot. */
    float flexiblePainting$getRotationYaw();

    float flexiblePainting$getRotationPitch();

    void flexiblePainting$setRotation(float yaw, float pitch);

    enum SurfaceType {
        WALL(0),
        FLOOR(1),
        CEILING(2);

        private final int id;

        SurfaceType(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public static SurfaceType fromId(int id) {
            for (SurfaceType type : values()) {
                if (type.id == id) {
                    return type;
                }
            }
            return WALL;
        }
    }
}
