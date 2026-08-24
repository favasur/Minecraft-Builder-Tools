package net.buildertools.flexiblepainting.mixin;

import java.util.Optional;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.buildertools.flexiblepainting.util.FlexiblePaintingHelper;
import net.buildertools.flexiblepainting.util.FlexiblePlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntityItem.class)
public abstract class PaintingItemMixin {
    @Shadow @Final private EntityType<? extends HangingEntity> type;

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void flexiblePainting$useOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        // Painting mods commonly register a distinct EntityType whose base class is a
        // Painting subclass. Match the entity class instead of the vanilla singleton so those
        // items receive the same flexible floor/ceiling placement.
        if (!Painting.class.isAssignableFrom(type.getBaseClass())) return;
        BlockPos clickedPos = context.getClickedPos();
        Direction clickedFace = context.getClickedFace();
        BlockPos paintingPos = clickedPos.relative(clickedFace);
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        Level level = context.getLevel();
        if (player != null && !player.mayUseItemAt(paintingPos, clickedFace, stack)) { cir.setReturnValue(InteractionResult.FAIL); return; }
        FlexiblePlacement placement = flexiblePainting$placement(clickedFace, paintingPos, player);
        FlexiblePaintingHelper.beginCreating(placement.surfaceType());
        try {
            Optional<Painting> candidate = Painting.create(level, placement.pos(), placement.direction());
            if (candidate.isEmpty()) { cir.setReturnValue(InteractionResult.CONSUME); return; }
            Painting painting = candidate.get();
            FlexiblePaintingHelper.setSurfaceType(painting, placement.surfaceType());
            TypedEntityData<EntityType<?>> data = stack.get(DataComponents.ENTITY_DATA);
            if (data != null) EntityType.updateCustomEntityTag(level, player, painting, data);
            if (!painting.survives()) { cir.setReturnValue(InteractionResult.CONSUME); return; }
            if (!level.isClientSide()) {
                painting.playPlacementSound();
                level.gameEvent((Entity) player, GameEvent.ENTITY_PLACE, painting.position());
                level.addFreshEntity(painting);
            }
            stack.shrink(1);
            cir.setReturnValue(InteractionResult.SUCCESS);
        } finally { FlexiblePaintingHelper.endCreating(); }
    }

    private static FlexiblePlacement flexiblePainting$placement(Direction face, BlockPos pos, Player player) {
        Direction playerDirection = player == null ? Direction.NORTH : player.getDirection();
        if (face == Direction.UP) {
            if (playerDirection.getAxis() == Direction.Axis.Z) playerDirection = playerDirection.getOpposite();
            return new FlexiblePlacement(pos, playerDirection, SurfaceType.FLOOR);
        }
        if (face == Direction.DOWN) {
            if (playerDirection == Direction.NORTH || playerDirection == Direction.SOUTH) playerDirection = playerDirection.getOpposite();
            return new FlexiblePlacement(pos, playerDirection, SurfaceType.CEILING);
        }
        return new FlexiblePlacement(pos, face, SurfaceType.WALL);
    }
}
