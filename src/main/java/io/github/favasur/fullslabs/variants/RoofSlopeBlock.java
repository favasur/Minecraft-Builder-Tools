package io.github.favasur.fullslabs.variants;

import io.github.favasur.fullslabs.variants.VariantGeometry.Box;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A Hytale Build roof-slope block: {@link Kind#SLOPE} (straight ramp), {@link Kind#OUTER}
 * (outer corner) and {@link Kind#INNER} (inner corner). The collision shape is built from the
 * very same world-space box list that is rendered, so render and collision always match 1:1.
 */
public class RoofSlopeBlock extends HorizontalDirectionalBlock {

	public enum Kind {
		SLOPE,
		OUTER,
		INNER
	}

	private final Kind kind;
	private final Map<Direction, VoxelShape> shapes;

	public RoofSlopeBlock(Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
		this.shapes = new HashMap<>();
		this.registerDefaultState(this.getStateDefinition().any().setValue(FACING, Direction.NORTH));
		for (Direction facing : Direction.Plane.HORIZONTAL) {
			this.shapes.put(facing, buildShape(kind, facing));
		}
	}

	public Kind getKind() {
		return kind;
	}

	private static VoxelShape buildShape(Kind kind, Direction facing) {
		List<Box> boxes = VariantGeometry.slopeBoxes(kind, facing);
		VoxelShape shape = Shapes.empty();
		for (Box b : boxes) {
			shape = Shapes.or(shape, Shapes.box(
					b.x1() / 16.0, b.y1() / 16.0, b.z1() / 16.0,
					b.x2() / 16.0, b.y2() / 16.0, b.z2() / 16.0));
		}
		return shape.optimize();
	}

	@Override
	protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
		return Block.simpleCodec(props -> new RoofSlopeBlock(Kind.SLOPE, props));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection());
	}

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return this.shapes.get(state.getValue(FACING));
	}

	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return this.shapes.get(state.getValue(FACING));
	}

	@Override
	public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
		return Shapes.block();
	}
}
