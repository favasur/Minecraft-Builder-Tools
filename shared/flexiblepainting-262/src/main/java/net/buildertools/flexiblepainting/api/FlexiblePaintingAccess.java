package net.buildertools.flexiblepainting.api;

public interface FlexiblePaintingAccess {
    SurfaceType flexiblePainting$getSurfaceType();
    void flexiblePainting$setSurfaceType(SurfaceType type);

    enum SurfaceType {
        WALL(0), FLOOR(1), CEILING(2);
        private final int id;
        SurfaceType(int id) { this.id = id; }
        public int id() { return id; }
        public static SurfaceType fromId(int id) {
            for (SurfaceType type : values()) if (type.id == id) return type;
            return WALL;
        }
    }
}
