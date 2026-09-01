package net.buildertools.mixin;

import io.github.favasur.smoothterrain.config.SmoothTerrainConfig;
import io.github.favasur.smoothterrain.mesh.MeshCache;
import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import io.github.favasur.smoothterrain.collision.MeshCollisionScope;
import net.buildertools.collision.RotatedCollisionProvider;
import net.buildertools.entity.OffGridBlockEntity;
import net.buildertools.server.RotationStore;
import net.buildertools.util.ArchGeometry;
import net.buildertools.util.BezierGeometry;
import net.buildertools.util.EllipseGeometry;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
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

/** Adds rendered smooth-terrain meshes and rotated-layer geometry to entity movement queries. */
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
        // Triangle-backed shapes implement Minecraft's axis sweep, but they are valid only for
        // entity movement. Block-collision consumers such as placement, suffocation and
        // pathfinding expect ordinary world-space VoxelShapes and may call bounds/toAabbs;
        // returning a mesh there can freeze the camera or corrupt those queries. The movement
        // mixin marks only Entity#collide with this scope.
        boolean movementCollision = MeshCollisionScope.isEntityMovement();
        boolean meshMode = SmoothTerrainConfig.Server.collisionsEnabled && movementCollision;

        // --- Smooth terrain: exact triangle collision replaces the per-block approximations.
        // BlockStateBaseMixin returns an empty shape for smoothable cells while this scope is
        // active, so no coarse cell shape needs to be guessed or removed from this list.
        if (meshMode) {
            var mesher = SmoothTerrainConfig.Server.mesher;
            int minCX = Mth.floor(box.minX - 2) >> 4;
            int maxCX = Mth.floor(box.maxX + 2) >> 4;
            int minCY = Mth.floor(box.minY - 2) >> 4;
            int maxCY = Mth.floor(box.maxY + 2) >> 4;
            int minCZ = Mth.floor(box.minZ - 2) >> 4;
            int maxCZ = Mth.floor(box.maxZ + 2) >> 4;
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cy = minCY; cy <= maxCY; cy++) {
                    for (int cz = minCZ; cz <= maxCZ; cz++) {
                        MeshCollisionShape sectionShape = MeshCache.getCollisionShape(level,
                                SectionPos.of(cx, cy, cz).origin(), mesher);
                        if (!sectionShape.isEmpty() && sectionShape.bounds().intersects(box)) {
                            // getBlockCollisions is allowed to return only shapes that may touch
                            // the query box. Without this check every generated section in the
                            // broadphase would make noCollision() fail merely because the mesh is
                            // non-empty, even when it is several blocks away.
                            shapes.add(sectionShape);
                        }
                    }
                }
            }
        }

        // The exact triangle shape is valid only inside Entity#collide. Do not expose a
        // voxelized approximation to other callers: its axis-aligned cells are the invisible
        // XYZ-grid hitbox this layer is designed to eliminate.
        if (!movementCollision) {
            cir.setReturnValue(shapes);
            return;
        }

        // --- The mod's block layer (rotated blocks, keyed by cell, rotated around their exact
        // model center - fractional for blocks snapped onto a rotated neighbor's grid). Each
        // rotated block's cell is air, so only the shape injected here represents it.
        for (Map.Entry<BlockPos, RotationData> e : RotationStore.getInBox(level, box)) {
            BlockPos pos = e.getKey();
            RotationData rot = e.getValue();
            // Arch / ellipse voussoirs collide against their exact wedge mesh (deterministic
            // geometry - no baked model needed, so this works on any side).
            if (rot.arch() != null) {
                MeshCollisionShape archShape = new MeshCollisionShape(
                        ArchGeometry.wedgeTriangles(rot.arch()));
                if (!archShape.isEmpty() && archShape.bounds().intersects(box)) {
                    shapes.add(archShape);
                }
                continue;
            }
            if (rot.ellipse() != null) {
                MeshCollisionShape ellipseShape = new MeshCollisionShape(
                        EllipseGeometry.wedgeTriangles(rot.ellipse()));
                if (!ellipseShape.isEmpty() && ellipseShape.bounds().intersects(box)) {
                    shapes.add(ellipseShape);
                }
                continue;
            }
            if (rot.bezier() != null) {
                MeshCollisionShape bezierShape = new MeshCollisionShape(
                        BezierGeometry.wedgeTriangles(rot.bezier()));
                if (!bezierShape.isEmpty() && bezierShape.bounds().intersects(box)) {
                    shapes.add(bezierShape);
                }
                continue;
            }
            Vec3 c = rot.center(pos);
            List<MeshCollisionShape.Tri> tris = RotatedCollisionProvider.triangles(rot, pos, level);
            MeshCollisionShape exact = tris != null
                    ? new MeshCollisionShape(tris)
                    : null;
            if (exact == null) {
                VoxelShape fallbackBase = rot.state().getCollisionShape(level, pos);
                if (!fallbackBase.isEmpty()) {
                    exact = MeshCollisionShape.fromVoxelShape(fallbackBase, c.x, c.y, c.z,
                            rot.yaw(), rot.pitch());
                }
            }
            if (exact != null && !exact.isEmpty() && exact.bounds().intersects(box)) {
                shapes.add(exact);
            }
        }
        // Legacy Air Placement entities use the same movement-only geometry bridge.
        for (OffGridBlockEntity e : level.getEntitiesOfClass(OffGridBlockEntity.class, box.inflate(1.5))) {
            if (!e.isSolidCollidable()) {
                continue;
            }
            BlockState state = e.getRepresentedState();
            net.minecraft.world.phys.Vec3 c = e.modelCenter();
            RotationData legacyRotation = new RotationData(state, e.getPlacementYaw(),
                    e.getPlacementPitch(), e.isBillboard(), c);
            List<MeshCollisionShape.Tri> tris = RotatedCollisionProvider.triangles(legacyRotation,
                    e.blockPosition(), level);
            MeshCollisionShape exact = tris != null
                    ? new MeshCollisionShape(tris)
                    : null;
            if (exact == null) {
                VoxelShape fallbackBase = state.getCollisionShape(level, BlockPos.ZERO);
                if (!fallbackBase.isEmpty()) {
                    exact = MeshCollisionShape.fromVoxelShape(fallbackBase, c.x, c.y, c.z,
                            e.getPlacementYaw(), e.getPlacementPitch());
                }
            }
            if (exact != null && !exact.isEmpty() && exact.bounds().intersects(box)) {
                shapes.add(exact);
            }
        }
        cir.setReturnValue(shapes);
    }

}
