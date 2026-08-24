package net.buildertools.flexiblepainting.util;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FlexiblePaintingGeometry {
    private static final double WALL_OFFSET = 0.46875;
    private static final double EDGE_THICKNESS = 0.03125;
    private FlexiblePaintingGeometry() { }

    public static AABB boundingBox(Painting painting, SurfaceType type) {
        BlockPos pos = painting.getPos();
        Direction direction = painting.getDirection();
        int width = painting.getVariant().value().width();
        int height = painting.getVariant().value().height();
        double centerX = pos.getX() + 0.5 - direction.getStepX() * WALL_OFFSET;
        double centerZ = pos.getZ() + 0.5 - direction.getStepZ() * WALL_OFFSET;
        Direction left = direction.getCounterClockWise();
        double parityOffset = width % 2 == 0 ? 0.5 : 0.0;
        centerX += left.getStepX() * parityOffset;
        centerZ += left.getStepZ() * parityOffset;
        double halfX = direction.getAxis() == Direction.Axis.Z ? width / 2.0 : height / 2.0;
        double halfZ = direction.getAxis() == Direction.Axis.Z ? height / 2.0 : width / 2.0;
        double y = type == SurfaceType.FLOOR ? pos.getY() : pos.getY() + 1.0;
        return new AABB(centerX - halfX, type == SurfaceType.FLOOR ? y : y - EDGE_THICKNESS,
                centerZ - halfZ, centerX + halfX, type == SurfaceType.FLOOR ? y + EDGE_THICKNESS : y,
                centerZ + halfZ);
    }

    public static boolean survives(Painting painting, SurfaceType type) {
        Level level = painting.level();
        AABB box = painting.getBoundingBox();
        if (!level.noCollision(painting, box)) return false;
        BlockPos support = type == SurfaceType.FLOOR ? painting.getPos().below() : painting.getPos().above();
        if (!level.getBlockState(support).isSolid()) return false;
        for (Entity other : level.getEntities(painting, box.inflate(0.01), entity -> entity instanceof HangingEntity && entity != painting && entity.isAlive())) {
            if (box.intersects(other.getBoundingBox())) return false;
        }
        return true;
    }

    public static void applyBoundingBox(Painting painting, SurfaceType type) {
        AABB box = boundingBox(painting, type);
        Vec3 center = box.getCenter();
        painting.setPosRaw(center.x, center.y, center.z);
        painting.setBoundingBox(box);
    }
}
