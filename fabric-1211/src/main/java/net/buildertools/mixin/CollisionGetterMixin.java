package net.buildertools.mixin;

import net.buildertools.server.RotationStore;
import net.buildertools.util.OffGridTransform;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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
        for (Map.Entry<BlockPos, RotationData> e : RotationStore.getInBox(level, box)) {
            BlockPos pos = e.getKey();
            RotationData rot = e.getValue();
            VoxelShape base = rot.state().getCollisionShape(level, pos);
            if (base.isEmpty()) {
                continue;
            }
            shapes.add(OffGridTransform.rotatedShape(rot.state(), base, rot.yaw(), rot.pitch())
                    .move(pos.getX(), pos.getY(), pos.getZ()));
        }
        cir.setReturnValue(shapes);
    }
}
