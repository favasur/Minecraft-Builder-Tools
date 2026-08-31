package net.buildertools.mixin;

import io.github.favasur.smoothterrain.collision.MeshCollisionScope;
import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.server.RotationStore;
import net.buildertools.util.ArchGeometry;
import net.buildertools.util.EllipseGeometry;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The physical core of rotated blocks: every block-collision query also collects the mod's rotated
 * blocks that intersect the queried box, as the block's OWN shape rotated around its cell center
 * (voxelized). Minecraft's movement code then slides against the rotated faces exactly like it
 * slides against normal blocks - the collision matches the rotated render.
 *
 * <p>Legacy entity blocks (air-placed, possibly fractional model centers) get the same treatment:
 * their own entity collision is disabled (an entity can only be a single axis-aligned box, which
 * would leave invisible corners around a rotated cube), and the rotated voxel shape is added here
 * instead, so the hitbox matches the visual there too.
 */
@Mixin(CollisionGetter.class)
public interface CollisionGetterMixin {
    @Inject(method = "getBlockCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/lang/Iterable;",
            at = @At("RETURN"), cancellable = true)
    private void buildertools$rotatedCollisions(Entity entity, AABB box,
                                                CallbackInfoReturnable<Iterable<VoxelShape>> cir) {
        if (!((Object) this instanceof Level level)) {
            return;
        }
        List<VoxelShape> shapes = new ArrayList<>();
        for (VoxelShape shape : cir.getReturnValue()) {
            shapes.add(shape);
        }
        // The mod's block layer (rotated blocks, keyed by cell, rotated around their exact model
        // center - fractional for blocks snapped onto a rotated neighbor's grid).
        for (Map.Entry<BlockPos, RotationData> e : RotationStore.getInBox(level, box)) {
            BlockPos pos = e.getKey();
            RotationData rot = e.getValue();
            // Arch voussoirs collide against their exact wedge mesh (deterministic geometry - no
            // baked model needed, so this works on any side). Triangle-backed shapes are valid
            // only inside Entity#collide (the movement scope); other getBlockCollisions
            // consumers such as suffocation and pathfinding expect ordinary VoxelShapes and
            // would read the mesh's empty discrete grid as no collision at all.
            if (rot.arch() != null) {
                if (MeshCollisionScope.isEntityMovement()) {
                    MeshCollisionShape archShape = new MeshCollisionShape(
                            ArchGeometry.wedgeTriangles(rot.arch()));
                    if (!archShape.isEmpty() && archShape.bounds().intersects(box)) {
                        shapes.add(archShape);
                    }
                }
                continue;
            }
            if (rot.ellipse() != null) {
                if (MeshCollisionScope.isEntityMovement()) {
                    MeshCollisionShape ellipseShape = new MeshCollisionShape(
                            EllipseGeometry.wedgeTriangles(rot.ellipse()));
                    if (!ellipseShape.isEmpty() && ellipseShape.bounds().intersects(box)) {
                        shapes.add(ellipseShape);
                    }
                }
                continue;
            }
            // Prefer the block's RENDERED model as the voxelization base (stair notches, thin
            // fence posts), falling back to the collision shape when no model is available.
            VoxelShape base = OffGridTransform.modelShape(rot.state());
            if (base == null) {
                base = rot.state().getCollisionShape(level, pos);
            }
            if (base.isEmpty()) {
                continue;
            }
            Vec3 c = rot.center(pos);
            shapes.add(OffGridTransform.rotatedShape(rot.state(), base, rot.yaw(), rot.pitch(),
                    c.x, c.y, c.z));
        }
        // Legacy entity blocks (air-placed, fractional centers): collide via the rotated voxel
        // shape around their own model center, never via their axis-aligned bounding box.
        for (OffGridBlockEntity e : level.getEntitiesOfClass(OffGridBlockEntity.class, box.inflate(1.5))) {
            if (!e.isSolidCollidable()) {
                continue;
            }
            BlockState state = e.getRepresentedState();
            VoxelShape base = OffGridTransform.modelShape(state);
            if (base == null) {
                base = state.getCollisionShape(level, BlockPos.ZERO);
            }
            if (base.isEmpty()) {
                continue;
            }
            net.minecraft.world.phys.Vec3 c = e.modelCenter();
            shapes.add(OffGridTransform.rotatedShape(state, base,
                    e.getPlacementYaw(), e.getPlacementPitch(), c.x, c.y, c.z));
        }
        cir.setReturnValue(shapes);
    }
}
