package net.buildertools.mixin;

import net.buildertools.client.SelectionRenderer;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Forge 26.2 has no debug-renderer registration event, so the builder gizmo renderer is hooked
 * into the vanilla {@link DebugRenderer} the same way Fabric's debug API does: after the renderer
 * list refreshes, our renderer is appended to it. {@code refreshRendererList} is called whenever
 * the debug entries change and when the frame needs the gizmos, so the renderer stays active.
 */
@Mixin(DebugRenderer.class)
public abstract class DebugRendererMixin {
    @Shadow
    @Final
    private List<DebugRenderer.SimpleDebugRenderer> renderers;

    @Inject(method = "refreshRendererList", at = @At("RETURN"))
    private void buildertools$addBuilderRenderer(CallbackInfo ci) {
        renderers.add(SelectionRenderer.instance());
    }
}
