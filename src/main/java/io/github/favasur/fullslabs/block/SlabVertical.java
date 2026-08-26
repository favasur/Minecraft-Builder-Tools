package io.github.favasur.fullslabs.block;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The 1.21.1 "vertical slab" graft. Instead of registering separate vertical blocks, the vertical
 * capability is applied directly to every {@link SlabBlock} (vanilla or modded) through two
 * state properties added by {@code SlabBlockMixin}: {@link #VERTICAL} and {@link #DIRECTION}.
 * When {@code VERTICAL} is set, the slab stands on its edge, occupying the half of the block in
 * the {@link #DIRECTION} direction, at full height.
 *
 * <p>Placement, shapes, collision and rendering all follow from the two properties; drops,
 * sounds, pick-block, waterlogging and wax/oxidize behavior stay vanilla because the block
 * itself never changes.
 */
public final class SlabVertical {

    /** True when the slab stands vertically. Defaults to false so existing slabs are unaffected. */
    public static final BooleanProperty VERTICAL = BooleanProperty.create("vertical");

    /** Which half of the block the vertical slab occupies (a horizontal facing). */
    public static final EnumProperty<Direction> DIRECTION = BlockStateProperties.HORIZONTAL_FACING;

    private static final VoxelShape NORTH_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 8.0);
    private static final VoxelShape EAST_SHAPE = Block.box(8.0, 0.0, 0.0, 16.0, 16.0, 16.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(0.0, 0.0, 8.0, 16.0, 16.0, 16.0);
    private static final VoxelShape WEST_SHAPE = Block.box(0.0, 0.0, 0.0, 8.0, 16.0, 16.0);

    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.NORTH, NORTH_SHAPE,
            Direction.SOUTH, SOUTH_SHAPE,
            Direction.WEST, WEST_SHAPE,
            Direction.EAST, EAST_SHAPE);

    private SlabVertical() {
    }

    public static boolean isVertical(BlockState state) {
        return state.getOptionalValue(VERTICAL).orElse(false);
    }

    public static Direction occupiedHalf(BlockState state) {
        return state.getOptionalValue(DIRECTION).orElse(Direction.WEST);
    }

    public static VoxelShape shape(BlockState state) {
        return SHAPES.getOrDefault(occupiedHalf(state), Shapes.block());
    }

    /**
     * Support shape for vertical states: the full block with the two thin-edge strips removed.
     * The vanilla shape pipeline derives face support from the outermost cell layer of the
     * support shape, so a bare half-block's exposed faces come out empty or partial. A full
     * support block would over-correct: redstone dust would sit on the vertical slab's
     * half-depth top (on a horizontal slab's half-depth top it cannot) and attachments would
     * stick to the thin edges (horizontal slab sides allow none). Removing the outer cell layer
     * of the two faces perpendicular to the slab's big faces keeps the two big faces fully
     * supported - torches, levers, buttons and ladders attach there exactly like on a full
     * block - while the thin edges and the half-depth top/bottom are not fully supported,
     * matching the horizontal slab: redstone dust cannot sit on top and nothing attaches to
     * the thin sides. The collision shape (physics, conduction, light) is unaffected.
     */
    public static VoxelShape supportShape(BlockState state) {
        VoxelShape s = Shapes.block();
        if (occupiedHalf(state).getAxis() == Direction.Axis.Z) {
            // Big faces are NORTH/SOUTH; the thin edges are the EAST/WEST strips.
            s = Shapes.join(s, Block.box(0.0, 0.0, 1.0, 1.0, 16.0, 15.0), BooleanOp.ONLY_FIRST);
            s = Shapes.join(s, Block.box(15.0, 0.0, 1.0, 16.0, 16.0, 15.0), BooleanOp.ONLY_FIRST);
        } else {
            // Big faces are EAST/WEST; the thin edges are the NORTH/SOUTH strips.
            s = Shapes.join(s, Block.box(1.0, 0.0, 0.0, 15.0, 16.0, 1.0), BooleanOp.ONLY_FIRST);
            s = Shapes.join(s, Block.box(1.0, 0.0, 15.0, 15.0, 16.0, 16.0), BooleanOp.ONLY_FIRST);
        }
        return s;
    }

    /** The slab standing vertically, defaulting to the WEST half. */
    public static BlockState vertical(BlockState state) {
        if (!(state.getBlock() instanceof SlabBlock) || !state.hasProperty(VERTICAL)) {
            return state;
        }
        return state.setValue(VERTICAL, true).setValue(DIRECTION, Direction.WEST);
    }

    /** The horizontal, bottom, non-waterlogged state used as the model source for vertical states. */
    public static BlockState flat(BlockState state) {
        return state.setValue(VERTICAL, false)
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /**
     * Whether the click location falls inside the block space occupied by the slab (i.e. the
     * region behind its inner face). Clicking a vertical slab's inner face merges it into a full
     * double slab.
     */
    public static boolean isInsideSlab(BlockState state, BlockPos pos, Vec3 hit) {
        if (!isVertical(state)) {
            return false;
        }
        return switch (occupiedHalf(state)) {
            case NORTH -> hit.z - (double) pos.getZ() >= 0.5;
            case SOUTH -> hit.z - (double) pos.getZ() <= 0.5;
            case WEST -> hit.x - (double) pos.getX() >= 0.5;
            case EAST -> hit.x - (double) pos.getX() <= 0.5;
            default -> false;
        };
    }

    /**
     * Result state for a placement: {@code target} UP/DOWN produces the vanilla top/bottom slab,
     * a horizontal target produces a vertical slab. The occupied half reproduces the original
     * FullSlabs rule exactly: clicking the center of a horizontal block face hugs the wall (the
     * far half), while edge clicks and top/bottom face clicks pick the half via the player's yaw.
     */
    public static BlockState getTargetedState(SlabBlock slab, Direction blockFace, Direction target, double cameraYaw) {
        BlockState base = slab.defaultBlockState();
        return switch (target) {
            case UP -> base.setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
            case DOWN -> base.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
            default -> {
                Direction occupied;
                Direction.Axis faceAxis = blockFace.getAxis();
                if (faceAxis == target.getAxis()) {
                    occupied = target.getOpposite();
                } else {
                    float altYaw = faceAxis.isVertical() ? target.toYRot() : blockFace.toYRot();
                    double delta = wrapToMinus180to180(cameraYaw - altYaw);
                    boolean towards = faceAxis.isVertical()
                            ? Math.abs(delta) < 90.0
                            : delta < 0.0 == (blockFace.getCounterClockWise() == target);
                    occupied = towards ? target : target.getOpposite();
                }
                yield base.setValue(VERTICAL, true).setValue(DIRECTION, occupied);
            }
        };
    }

    private static double wrapToMinus180to180(double value) {
        return value < 0.0 ? 180.0 - Math.abs(value) % 360.0 : value - 180.0;
    }
}
