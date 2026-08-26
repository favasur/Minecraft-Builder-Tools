package net.buildertools.mixin;

import net.buildertools.item.EntityToolItem;
import net.buildertools.item.LaserToolItem;
import net.buildertools.item.PaintToolItem;
import net.buildertools.item.RulerToolItem;
import net.buildertools.item.ScatterToolItem;
import net.buildertools.item.SelectionToolItem;
import net.buildertools.item.SmoothToolItem;
import net.buildertools.server.RotationStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guards the vanilla mining flow: a rotated block's cell is air in the vanilla grid, so vanilla
 * mining would do nothing there (or, worse, break whatever is behind it). When the cursor targets
 * a rotated block, the vanilla destroy flow is suppressed - the mod's own LMB handler breaks the
 * block instead (instant in creative, progressive in survival).
 *
 * <p>Builder tools are exempt: the mod's tool handlers act on the {@code LeftClickBlock} event
 * (selection corner 1, ruler point A), which NeoForge fires from inside {@code startDestroyBlock} -
 * suppressing the flow at HEAD would swallow those clicks. The tool handlers cancel the event
 * themselves, so vanilla never actually destroys anything.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "startDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void buildertools$guardStartDestroy(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && RotationStore.hasRotation(mc.level, pos) && !holdsBuilderTool(mc)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z",
            at = @At("HEAD"), cancellable = true)
    private void buildertools$guardContinueDestroy(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && RotationStore.hasRotation(mc.level, pos) && !holdsBuilderTool(mc)) {
            cir.setReturnValue(false);
        }
    }

    /** Whether the player's main hand holds a Builder Tools tool whose clicks the mod handles itself. */
    private static boolean holdsBuilderTool(Minecraft mc) {
        if (mc.player == null) {
            return false;
        }
        Item item = mc.player.getMainHandItem().getItem();
        return item instanceof SelectionToolItem
                || item instanceof RulerToolItem
                || item instanceof LaserToolItem
                || item instanceof PaintToolItem
                || item instanceof ScatterToolItem
                || item instanceof SmoothToolItem
                || item instanceof EntityToolItem;
    }

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private void buildertools$guardDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && RotationStore.hasRotation(mc.level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
