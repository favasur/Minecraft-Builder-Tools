package net.buildertools.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.2 port of the 1.21.1 {@code ItemRendererMixin} (Hytale-style held item: builder tools render
 * the 3D paint brush model while held in hand and keep the flat icon in the GUI, on the ground and
 * in item frames). 26.2 removed {@code ItemRenderer} and resolves item models from the stack's
 * {@code ITEM_MODEL} data component inside {@link ItemModelResolver#appendItemLayers}; that lookup
 * is redirected here to the brush model for hand contexts. Flat contexts pass through untouched,
 * so they keep the tool's regular (flat) model.
 */
@Mixin(ItemModelResolver.class)
public abstract class ItemModelResolverMixin {

    private static final Identifier BRUSH_IN_HAND =
            Identifier.fromNamespaceAndPath("buildertools", "builder_brush");

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

    private static boolean isFlatContext(ItemDisplayContext context) {
        return context == ItemDisplayContext.GUI
                || context == ItemDisplayContext.GROUND
                || context == ItemDisplayContext.FIXED;
    }

    @WrapOperation(
        method = "appendItemLayers(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/ItemOwner;I)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;"
        )
    )
    private Object buildertools$brushModel(ItemStack stack, DataComponentType<?> type, Operation<Object> original,
                                           @Local(argsOnly = true) ItemDisplayContext displayContext) {
        Object value = original.call(stack, type);
        if (type == DataComponents.ITEM_MODEL && isBuilderTool(stack) && !isFlatContext(displayContext)) {
            return BRUSH_IN_HAND;
        }
        return value;
    }

}
