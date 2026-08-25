package net.buildertools.mixin;

import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the private {@code Display} setters so off-grid blocks can be rotated and billboarded. */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setTransformation")
    void buildertools$setTransformation(Transformation transformation);

    @Invoker("setBillboardConstraints")
    void buildertools$setBillboardConstraints(Display.BillboardConstraints constraints);
}
