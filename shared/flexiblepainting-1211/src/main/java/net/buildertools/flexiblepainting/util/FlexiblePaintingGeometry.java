package net.buildertools.flexiblepainting.util;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class FlexiblePaintingGeometry {
    private static final double WALL_OFFSET = 0.46875;
    private static final double EDGE_THICKNESS = 0.03125;

    private FlexiblePaintingGeometry() {
    }

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

        AABB unrotated;
        if (type == SurfaceType.FLOOR || type == SurfaceType.CEILING) {
            double halfX = direction.getAxis() == Direction.Axis.Z ? width / 2.0 : height / 2.0;
            double halfZ = direction.getAxis() == Direction.Axis.Z ? height / 2.0 : width / 2.0;
            double y = type == SurfaceType.FLOOR ? pos.getY() : pos.getY() + 1.0;
            unrotated = new AABB(centerX - halfX, type == SurfaceType.FLOOR ? y : y - EDGE_THICKNESS,
                    centerZ - halfZ, centerX + halfX, type == SurfaceType.FLOOR ? y + EDGE_THICKNESS : y,
                    centerZ + halfZ);
        } else {
            // This is the same box as Painting#calculateBoundingBox. Keeping the vanilla wall
            // geometry here is important: custom paintings still use their normal wall size when
            // their flexible rotation is zero, and the same base box is what the renderer rotates.
            double centerY = pos.getY() + 0.5 + (height % 2 == 0 ? 0.5 : 0.0);
            double center = direction.getAxis() == Direction.Axis.X ? 0.0625 : width;
            double depth = direction.getAxis() == Direction.Axis.Z ? 0.0625 : width;
            unrotated = AABB.ofSize(new Vec3(centerX, centerY, centerZ), center, height, depth);
        }

        float yaw = FlexiblePaintingHelper.getRotationYaw(painting);
        float pitch = FlexiblePaintingHelper.getRotationPitch(painting);
        if (yaw == 0.0f && pitch == 0.0f) {
            return unrotated;
        }
        return rotateBox(unrotated, yaw, pitch);
    }

    /** Rotates the same thin cuboid rendered by PaintingRenderer around its entity pivot. */
    private static AABB rotateBox(AABB box, float yaw, float pitch) {
        Vec3 center = box.getCenter();
        Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(-yaw))
                .mul(new Quaternionf().rotateX((float) Math.toRadians(pitch)));
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{box.minX, box.maxX}) {
            for (double y : new double[]{box.minY, box.maxY}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    Vector3f point = rotation.transform(new Vector3f(
                            (float) (x - center.x), (float) (y - center.y), (float) (z - center.z)),
                            new Vector3f());
                    double worldX = center.x + point.x;
                    double worldY = center.y + point.y;
                    double worldZ = center.z + point.z;
                    minX = Math.min(minX, worldX);
                    minY = Math.min(minY, worldY);
                    minZ = Math.min(minZ, worldZ);
                    maxX = Math.max(maxX, worldX);
                    maxY = Math.max(maxY, worldY);
                    maxZ = Math.max(maxZ, worldZ);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static boolean survives(Painting painting, SurfaceType type) {
        Level level = painting.level();
        AABB box = painting.getBoundingBox();
        if (!level.noCollision(painting, box)) {
            return false;
        }

        BlockPos support = switch (type) {
            case FLOOR -> painting.getPos().below();
            case CEILING -> painting.getPos().above();
            case WALL -> painting.getPos().relative(painting.getDirection().getOpposite());
        };
        if ((type == SurfaceType.FLOOR || type == SurfaceType.CEILING)
                && !level.getBlockState(support).isSolid()) {
            return false;
        }

        for (Entity other : level.getEntities(painting, box.inflate(0.01), entity ->
                entity instanceof HangingEntity && entity != painting && entity.isAlive())) {
            if (box.intersects(other.getBoundingBox())) {
                return false;
            }
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
