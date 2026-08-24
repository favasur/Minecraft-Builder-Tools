package net.buildertools.flexiblepainting.mixin;

import java.util.Optional;
import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.buildertools.flexiblepainting.api.FlexiblePaintingEntityAccess;
import net.buildertools.flexiblepainting.util.FlexiblePaintingHelper;
import net.buildertools.flexiblepainting.util.FlexiblePlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HangingEntityItem.class)
public abstract class PaintingItemMixin {
    @Shadow
    @Final
    private EntityType<? extends HangingEntity> type;

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void flexiblePainting$useOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        // A number of painting mods register their own EntityType whose Java class extends
        // vanilla Painting. The original Flexible Painting mod only compared the type singleton,
        // which silently excluded those items. Use the type's declared base class instead; this
        // keeps item frames and other hanging entities on vanilla behavior.
        if (!Painting.class.isAssignableFrom(type.getBaseClass())) {
            return;
        }

        BlockPos clickedPos = context.getClickedPos();
        Direction clickedFace = context.getClickedFace();
        BlockPos paintingPos = clickedPos.relative(clickedFace);
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        Level level = context.getLevel();
        if (player != null && !player.mayUseItemAt(paintingPos, clickedFace, stack)) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }

        FlexiblePlacement placement = flexiblePainting$placement(clickedFace, paintingPos, player);
        FlexiblePaintingHelper.beginCreating(placement.surfaceType());
        try {
            // Painting.create() always constructs vanilla EntityType.PAINTING. First use it
            // only as the vanilla variant selector, then create the entity from this item's own
            // declared type so paintings supplied by other mods keep their renderer, data, and
            // entity id while receiving the same flexible placement behavior.
            Optional<Painting> candidate = Painting.create(level, placement.pos(), placement.direction());
            if (candidate.isEmpty()) {
                cir.setReturnValue(InteractionResult.CONSUME);
                return;
            }
            Painting painting = flexiblePainting$createEntity(level, placement, candidate.get());
            if (painting == null) {
                cir.setReturnValue(InteractionResult.CONSUME);
                return;
            }
            FlexiblePaintingHelper.setSurfaceType(painting, placement.surfaceType());
            CustomData entityData = stack.getOrDefault(DataComponents.ENTITY_DATA, CustomData.EMPTY);
            if (!entityData.isEmpty()) {
                EntityType.updateCustomEntityTag(level, player, painting, entityData);
            }
            if (!painting.survives()) {
                cir.setReturnValue(InteractionResult.CONSUME);
                return;
            }
            if (!level.isClientSide) {
                painting.playPlacementSound();
                level.gameEvent((Entity) player, GameEvent.ENTITY_PLACE, painting.position());
                level.addFreshEntity(painting);
            }
            stack.shrink(1);
            cir.setReturnValue(InteractionResult.sidedSuccess(level.isClientSide));
        } finally {
            FlexiblePaintingHelper.endCreating();
        }
    }

    @Unique
    private Painting flexiblePainting$createEntity(Level level, FlexiblePlacement placement, Painting template) {
        Entity created = type.create(level);
        if (!(created instanceof Painting painting)) {
            return null;
        }
        if (!(painting instanceof FlexiblePaintingEntityAccess access)) {
            // The mixin is required for the vanilla type. Do not silently replace a modded
            // painting with vanilla if another mixin/config prevented the bridge from applying.
            return type == EntityType.PAINTING ? template : null;
        }
        access.flexiblePainting$initialize(placement.pos(), placement.direction());
        painting.setVariant(template.getVariant());
        return painting;
    }

    @Unique
    private static FlexiblePlacement flexiblePainting$placement(Direction clickedFace, BlockPos pos, Player player) {
        Direction playerDirection = player == null ? Direction.NORTH : player.getDirection();
        if (clickedFace == Direction.UP) {
            if (playerDirection.getAxis() == Direction.Axis.Z) {
                playerDirection = playerDirection.getOpposite();
            }
            return new FlexiblePlacement(pos, playerDirection, SurfaceType.FLOOR);
        }
        if (clickedFace == Direction.DOWN) {
            if (playerDirection == Direction.NORTH || playerDirection == Direction.SOUTH) {
                playerDirection = playerDirection.getOpposite();
            }
            return new FlexiblePlacement(pos, playerDirection, SurfaceType.CEILING);
        }
        return new FlexiblePlacement(pos, clickedFace, SurfaceType.WALL);
    }
}
