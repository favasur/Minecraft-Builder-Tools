package net.buildertools.flexiblepainting.util;

import net.buildertools.flexiblepainting.api.FlexiblePaintingAccess.SurfaceType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record FlexiblePlacement(BlockPos pos, Direction direction, SurfaceType surfaceType) { }
