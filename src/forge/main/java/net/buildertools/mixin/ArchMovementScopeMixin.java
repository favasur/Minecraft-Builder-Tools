package net.buildertools.mixin;

import io.github.favasur.smoothterrain.collision.MeshCollisionScope;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks the small portion of Minecraft's movement pipeline that may consume exact triangle-mesh
 * collision shapes ({@code Entity#collide}, where {@code getBlockCollisions} feeds the axis sweep).
 * The marker is thread-local so camera, placement, suffocation and pathfinding queries on the same
 * thread keep receiving ordinary VoxelShapes - a triangle-backed shape returned there would be
 * read as empty (its discrete grid is empty) and corrupt those queries.
 */
@Mixin(Entity.class)
public abstract class ArchMovementScopeMixin {
    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("HEAD"))
    private void buildertools$beginMovementCollision(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        MeshCollisionScope.enterMovement();
    }

    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At("RETURN"))
    private void buildertools$endMovementCollision(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        MeshCollisionScope.exitMovement();
    }
}
