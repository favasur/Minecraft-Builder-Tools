package net.buildertools.flexiblepainting.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Internal bridge for painting items to initialize a modded HangingEntity subclass safely. */
public interface FlexiblePaintingEntityAccess {
    void flexiblePainting$initialize(BlockPos pos, Direction direction);

    void flexiblePainting$refreshBoundingBox();
}
