package io.github.favasur.fullslabs.block;

import io.github.favasur.fullslabs.handlers.MixedHandlers;
import io.github.favasur.fullslabs.util.Utility;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

@MethodsReturnNonnullByDefault
public class VerticalSlabBlock extends Block implements SimpleWaterloggedBlock {
    private static final Map<SlabBlock, VerticalSlabBlock> MAP = new HashMap<>();
    public static final EnumProperty<Direction> DIRECTION = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<VerticalType> TYPE = EnumProperty.create("type", VerticalType.class);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final VoxelShape NORTH_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 8.0);
    public static final VoxelShape EAST_SHAPE = Block.box(8.0, 0.0, 0.0, 16.0, 16.0, 16.0);
    public static final VoxelShape SOUTH_SHAPE = Block.box(0.0, 0.0, 8.0, 16.0, 16.0, 16.0);
    public static final VoxelShape WEST_SHAPE = Block.box(0.0, 0.0, 0.0, 8.0, 16.0, 16.0);
    public static final Map<SlabBlock, VerticalSlabBlock> MAP_VIEW = Collections.unmodifiableMap(MAP);
    public final SlabBlock parent;

    public VerticalSlabBlock(SlabBlock block, BlockBehaviour.Properties properties) {
        super(properties);
        this.parent = block;
        MAP.put(block, this);
        this.registerDefaultState(this.defaultBlockState()
                .setValue(DIRECTION, Direction.WEST)
                .setValue(TYPE, VerticalType.TOWARDS)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return state.getValue(TYPE) != VerticalType.FULL;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DIRECTION, TYPE, WATERLOGGED);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        VerticalType type = state.getValue(TYPE);
        if (type == VerticalType.FULL) {
            return Shapes.block();
        }
        boolean towards = type == VerticalType.TOWARDS;
        return switch (state.getValue(DIRECTION)) {
            case NORTH -> towards ? NORTH_SHAPE : SOUTH_SHAPE;
            case EAST -> towards ? EAST_SHAPE : WEST_SHAPE;
            case SOUTH -> towards ? SOUTH_SHAPE : NORTH_SHAPE;
            case WEST -> towards ? WEST_SHAPE : EAST_SHAPE;
            default -> throw new IllegalArgumentException();
        };
    }

    @Override
    protected boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        ItemStack stack = context.getItemInHand();
        VerticalType type = state.getValue(TYPE);
        if (type == VerticalType.FULL) {
            return false;
        }
        Item item = stack.getItem();
        if (!(item instanceof BlockItem blockItem)) {
            return false;
        }
        Block block = blockItem.getBlock();
        if (!(block instanceof SlabBlock)) {
            return false;
        }
        if (!(block == this.parent || (MixedHandlers.hasHandler(block) && MixedHandlers.hasHandler(this)))) {
            return false;
        }
        if (context.replacingClickedOnBlock()) {
            return Utility.isInsideSlab(state, context.getClickedPos(), context.getClickLocation());
        }
        return true;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public boolean placeLiquid(LevelAccessor world, BlockPos pos, BlockState state, FluidState fluidState) {
        if (state.getValue(TYPE) == VerticalType.FULL || state.getValue(WATERLOGGED)) {
            return false;
        }
        if (!world.isClientSide()) {
            world.setBlock(pos, state.setValue(WATERLOGGED, true), 3);
        }
        world.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(world));
        return true;
    }

    @Override
    public boolean canPlaceLiquid(Player filler, BlockGetter world, BlockPos pos, BlockState state, Fluid fluid) {
        return state.getValue(TYPE) != VerticalType.FULL && !state.getValue(WATERLOGGED);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor world, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            world.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
        }
        return super.updateShape(state, direction, neighborState, world, pos, neighborPos);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return type == PathComputationType.WATER && state.getFluidState().is(FluidTags.WATER);
    }

    @Override
    public Item asItem() {
        return this.parent.asItem();
    }

    public static VerticalSlabBlock getVertical(SlabBlock block) {
        return MAP.get(block);
    }

    public static Optional<VerticalSlabBlock> tryGetVertical(Block block) {
        if (block instanceof SlabBlock slab) {
            return Optional.ofNullable(MAP.get(slab));
        }
        if (block instanceof VerticalSlabBlock slab) {
            return Optional.of(slab);
        }
        return Optional.empty();
    }

    public static SlabBlock getRoot(Block block) {
        return tryGetRoot(block).orElseThrow(() -> new IllegalArgumentException("Not a slab or missing vertical!"));
    }

    public static Optional<SlabBlock> tryGetRoot(Block block) {
        if (block instanceof SlabBlock slab && hasVertical(slab)) {
            return Optional.of(slab);
        }
        if (block instanceof VerticalSlabBlock slab) {
            return Optional.of(slab.parent);
        }
        return Optional.empty();
    }

    public static boolean hasVertical(SlabBlock block) {
        return MAP.containsKey(block);
    }

    public enum VerticalType implements StringRepresentable {
        TOWARDS("towards"),
        AWAY("away"),
        FULL("full");

        private final String name;

        VerticalType(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}
