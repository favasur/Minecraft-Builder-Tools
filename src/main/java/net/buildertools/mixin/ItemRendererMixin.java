package net.buildertools.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hytale-style held item: builder tools render the 3D paint brush model while held in hand, and
 * keep the flat icon in the GUI (inventory), on the ground and in item frames - the same
 * one-model-in-hand / another-in-GUI pattern vanilla uses for the trident.
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {
    private static final ModelResourceLocation BRUSH_IN_HAND =
            ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath("buildertools", "builder_brush"));

    private static boolean isBuilderTool(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof SelectionToolItem
                || item instanceof EntityToolItem
                || item instanceof RulerToolItem
                || item instanceof LaserToolItem
                || item instanceof ScatterToolItem
                || item instanceof SmoothToolItem
                || item instanceof PaintToolItem;
    }

    @Inject(
        method = "getModel(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)Lnet/minecraft/client/resources/model/BakedModel;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void builderTools$brushModel(ItemStack stack, Level level, LivingEntity entity, int seed,
                                         CallbackInfoReturnable<BakedModel> cir) {
        if (isBuilderTool(stack)) {
            cir.setReturnValue(((ItemRenderer) (Object) this).getItemModelShaper().getModelManager().getModel(BRUSH_IN_HAND));
        }
    }

    /** GUI, ground and item frames keep the flat icon; only the hands render the brush. */
    @ModifyVariable(
        method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V",
        at = @At("HEAD"),
        ordinal = 7,
        argsOnly = true
    )
    private BakedModel builderTools$flatIcon(BakedModel model, ItemStack stack, ItemDisplayContext displayContext,
                                             boolean leftHand, PoseStack poseStack, MultiBufferSource bufferSource,
                                             int combinedLight, int combinedOverlay) {
        if (isBuilderTool(stack)
                && (displayContext == ItemDisplayContext.GUI
                    || displayContext == ItemDisplayContext.GROUND
                    || displayContext == ItemDisplayContext.FIXED)) {
            return ((ItemRenderer) (Object) this).getItemModelShaper().getItemModel(stack);
        }
        return model;
    }
}
