package io.github.favasur.fullslabs.util;

import com.google.common.collect.BiMap;
import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.util.SlabContext;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public class Utility {
    private static double wrapToMinus180to180(double value) {
        return value < 0.0 ? 180.0 - Math.abs(value) % 360.0 : value - 180.0;
    }

    public static BlockState getTargetedState(SlabBlock slab, Direction blockFace, Direction target, double cameraYaw) {
        VerticalSlabBlock vertical = VerticalSlabBlock.getVertical(slab);
        return switch (target) {
            case UP -> slab.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
            case DOWN -> slab.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
            default -> {
                Direction.Axis faceAxis = blockFace.getAxis();
                if (faceAxis == target.getAxis()) {
                    yield vertical.defaultBlockState()
                            .setValue(VerticalSlabBlock.TYPE, VerticalSlabBlock.VerticalType.AWAY)
                            .setValue(VerticalSlabBlock.DIRECTION, target);
                }
                float altYaw = faceAxis.isVertical() ? target.toYRot() : blockFace.toYRot();
                double delta = Utility.wrapToMinus180to180(cameraYaw - altYaw);
                boolean towards = faceAxis.isVertical() ? Math.abs(delta) < 90.0 : delta < 0.0 == (blockFace.getCounterClockWise() == target);
                yield vertical.defaultBlockState()
                        .setValue(VerticalSlabBlock.TYPE, towards ? VerticalSlabBlock.VerticalType.TOWARDS : VerticalSlabBlock.VerticalType.AWAY)
                        .setValue(VerticalSlabBlock.DIRECTION, towards ? target : target.getOpposite());
            }
        };
    }

    public static boolean isSlabWithVertical(ItemStack stack) {
        return Utility.isSlabWithVertical((ItemLike)stack.getItem());
    }

    public static boolean isSlabWithVertical(ItemLike item) {
        BlockItem blockItem;
        return item instanceof BlockItem && Utility.isSlabWithVertical((blockItem = (BlockItem)item).getBlock());
    }

    public static boolean isSlabWithVertical(BlockState state) {
        return Utility.isSlabWithVertical(state.getBlock());
    }

    public static boolean isSlabWithVertical(Block block) {
        SlabBlock slab;
        return block instanceof VerticalSlabBlock || block instanceof SlabBlock && VerticalSlabBlock.hasVertical(slab = (SlabBlock)block);
    }

    public static boolean isSlab(ItemStack stack) {
        return Utility.isSlab((ItemLike)stack.getItem());
    }

    public static boolean isSlab(ItemLike item) {
        BlockItem blockItem;
        return item instanceof BlockItem && Utility.isSlab((blockItem = (BlockItem)item).getBlock());
    }

    public static boolean isSlab(BlockState state) {
        return Utility.isSlab(state.getBlock());
    }

    public static boolean isSlab(Block block) {
        return block instanceof SlabBlock || block instanceof VerticalSlabBlock;
    }

    public static boolean isDoubleSlab(BlockState state) {
        return state.getBlock() instanceof SlabBlock && state.getValue((Property)BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE || state.getBlock() instanceof VerticalSlabBlock && state.getValue(VerticalSlabBlock.TYPE) == VerticalSlabBlock.VerticalType.FULL;
    }

    public static boolean isInsideSlab(BlockState state, BlockPos pos, Vec3 hit) {
        Block block = state.getBlock();
        if (!Utility.isSlab(block)) {
            return false;
        }
        if (block instanceof SlabBlock) {
            SlabType type = (SlabType)state.getValue((Property)BlockStateProperties.SLAB_TYPE);
            if (type == SlabType.DOUBLE) {
                return false;
            }
            double diff = hit.y - (double)pos.getY();
            return type == SlabType.BOTTOM ? diff >= 0.5 : diff <= 0.5;
        }
        VerticalSlabBlock.VerticalType type = (VerticalSlabBlock.VerticalType)((Object)state.getValue(VerticalSlabBlock.TYPE));
        if (type == VerticalSlabBlock.VerticalType.FULL) {
            return false;
        }
        Direction dir = (Direction)state.getValue(VerticalSlabBlock.DIRECTION);
        dir = type == VerticalSlabBlock.VerticalType.TOWARDS ? dir : dir.getOpposite();
        return switch (dir) {
            case Direction.NORTH -> {
                if (hit.z - (double)pos.getZ() >= 0.5) {
                    yield true;
                }
                yield false;
            }
            case Direction.SOUTH -> {
                if (hit.z - (double)pos.getZ() <= 0.5) {
                    yield true;
                }
                yield false;
            }
            case Direction.WEST -> {
                if (hit.x - (double)pos.getX() >= 0.5) {
                    yield true;
                }
                yield false;
            }
            case Direction.EAST -> {
                if (hit.x - (double)pos.getX() <= 0.5) {
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    public static HitResult crosshair(Player player) {
        return player.pick(player.blockInteractionRange(), 1.0f, false);
    }

    public static HitResult crosshair(@Nullable Player player, boolean isClient) {
        if (isClient) {
            return Minecraft.getInstance().hitResult;
        }
        if (player == null) {
            throw new IllegalArgumentException("Player is null on serverside!");
        }
        return Utility.crosshair(player);
    }

    @Nullable
    public static StatePair breakHalf(BlockGetter view, BlockState state, BlockPos pos, HitResult crosshair) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(state);
        Objects.requireNonNull(pos);
        Objects.requireNonNull(crosshair);
        Vec3 hit = crosshair.getLocation();
        Block block = state.getBlock();
        if (state.is((Block)SlabRegistry.MIXED_SLAB)) {
            MixedSlabBlock.MixedType type = (MixedSlabBlock.MixedType)((Object)state.getValue(MixedSlabBlock.TYPE));
            boolean towards = type.isAxisTargetTowards(hit, pos);
            return SlabRegistry.MIXED_SLAB.forward(view, pos, ctx -> {
                MixedSlabBlockEntity entity = ctx.blockEntityOrThrow();
                return new StatePair(entity.getState(towards), entity.getState(!towards));
            });
        }
        if (!Utility.isDoubleSlab(state)) {
            return null;
        }
        MixedSlabBlock.MixedType type = MixedSlabBlock.MixedType.fromState(state);
        boolean towards = type.isAxisTargetTowards(hit, pos);
        if (block instanceof SlabBlock) {
            return new StatePair(
                    state.setValue(BlockStateProperties.SLAB_TYPE, towards ? SlabType.TOP : SlabType.BOTTOM),
                    state.setValue(BlockStateProperties.SLAB_TYPE, towards ? SlabType.BOTTOM : SlabType.TOP));
        }
        if (block instanceof VerticalSlabBlock) {
            return new StatePair(
                    state.setValue(VerticalSlabBlock.TYPE, towards ? VerticalSlabBlock.VerticalType.TOWARDS : VerticalSlabBlock.VerticalType.AWAY),
                    state.setValue(VerticalSlabBlock.TYPE, towards ? VerticalSlabBlock.VerticalType.AWAY : VerticalSlabBlock.VerticalType.TOWARDS));
        }
        throw new AssertionError();
    }

    public static BlockState targetedHalf(BlockGetter world, BlockState state, BlockPos pos, Vec3 hit) {
        if (!Utility.isDoubleSlab(state) && !state.is((Block)SlabRegistry.MIXED_SLAB)) {
            return state;
        }
        Block block = state.getBlock();
        if (block == SlabRegistry.MIXED_SLAB) {
            return SlabRegistry.MIXED_SLAB.forwardSideValue(world, pos, hit, SlabContext::mainState);
        }
        boolean towards = MixedSlabBlock.MixedType.fromState(state).isAxisTargetTowards(hit, pos);
        if (block instanceof SlabBlock) {
            return state.setValue(BlockStateProperties.SLAB_TYPE, towards ? SlabType.TOP : SlabType.BOTTOM);
        }
        return state.setValue(VerticalSlabBlock.TYPE, towards ? VerticalSlabBlock.VerticalType.TOWARDS : VerticalSlabBlock.VerticalType.AWAY);
    }

    public static Direction slabDirection(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof SlabBlock) {
            SlabType type = state.getValue(BlockStateProperties.SLAB_TYPE);
            if (type == SlabType.DOUBLE) {
                throw new IllegalArgumentException("Not a half-slab!");
            }
            return type == SlabType.TOP ? Direction.UP : Direction.DOWN;
        }
        if (block instanceof VerticalSlabBlock) {
            VerticalSlabBlock.VerticalType type = state.getValue(VerticalSlabBlock.TYPE);
            if (type == VerticalSlabBlock.VerticalType.FULL) {
                throw new IllegalArgumentException("Not a half-slab!");
            }
            Direction direction = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return type == VerticalSlabBlock.VerticalType.TOWARDS ? direction : direction.getOpposite();
        }
        throw new IllegalArgumentException("Not a half-slab!");
    }

    public static boolean isSlabTowards(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof SlabBlock) {
            return state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP;
        }
        if (block instanceof VerticalSlabBlock) {
            return state.getValue(VerticalSlabBlock.TYPE) == VerticalSlabBlock.VerticalType.TOWARDS;
        }
        return false;
    }

    public static BlockState setSlabTowards(BlockState state, boolean towards) {
        Block block = state.getBlock();
        if (block instanceof SlabBlock) {
            return state.setValue(BlockStateProperties.SLAB_TYPE, towards ? SlabType.TOP : SlabType.BOTTOM);
        }
        if (block instanceof VerticalSlabBlock) {
            return state.setValue(VerticalSlabBlock.TYPE, towards ? VerticalSlabBlock.VerticalType.TOWARDS : VerticalSlabBlock.VerticalType.AWAY);
        }
        throw new IllegalStateException("Unexpected value: " + state.getBlock());
    }

    public static Optional<Block> getWaxed(Block unwaxed) {
        return Optional.ofNullable((Block)((BiMap)HoneycombItem.WAXABLES.get()).get((Object)unwaxed));
    }

    public record StatePair(BlockState towards, BlockState away) {
    }
}

