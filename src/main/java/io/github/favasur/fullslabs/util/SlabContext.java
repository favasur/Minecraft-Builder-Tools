package io.github.favasur.fullslabs.util;

import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.block.entity.MixedSlabBlockEntity;
import io.github.favasur.fullslabs.handlers.MixedHandler;
import io.github.favasur.fullslabs.handlers.MixedHandlers;
import io.github.favasur.fullslabs.util.Utility;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

public final class SlabContext {
    private static final Supplier<RuntimeException> MISSING_BE = () -> new RuntimeException("Missing mixed slab block entity!");
    private final BlockPos pos;
    private final MixedSlabBlock.MixedType type;
    private Side side;
    private BlockState rootState;
    @Nullable
    private MixedSlabBlockEntity blockEntity;
    private BlockState mainState;
    private BlockState otherState;

    public SlabContext(BlockGetter level, BlockPos pos, Side side) {
        this.rootState = level.getBlockState(pos);
        this.type = MixedSlabBlock.MixedType.fromState(this.rootState);
        this.pos = pos;
        this.recontextualize(level, side);
    }

    public Optional<MixedSlabBlockEntity> blockEntity() {
        return Optional.ofNullable(this.blockEntity);
    }

    public MixedSlabBlockEntity blockEntityOrThrow() {
        if (this.blockEntity == null) {
            throw MISSING_BE.get();
        }
        return this.blockEntity;
    }

    public Side side() {
        return this.side;
    }

    public BlockState state(Side side) {
        BlockState state;
        boolean isTowards = side.isTowards();
        boolean isMain = side == this.side;
        BlockState blockState = state = isMain ? this.mainState : this.otherState;
        if (state != null) {
            return state;
        }
        Block rootBlock = this.rootState.getBlock();
        state = rootBlock == SlabRegistry.MIXED_SLAB
                ? (this.blockEntity == null
                        ? this.rootState.getValue(MixedSlabBlock.TYPE).state((SlabBlock) Blocks.STONE_SLAB, isTowards)
                        : this.blockEntity.getState(isTowards))
                : (Utility.isDoubleSlab(this.rootState)
                        ? Utility.setSlabTowards(this.rootState, isTowards)
                        : (Utility.isSlabTowards(this.rootState) == isTowards
                                ? this.rootState.setValue(BlockStateProperties.WATERLOGGED, false)
                                : Blocks.AIR.defaultBlockState()));
        if (isMain) {
            this.mainState = state;
        } else {
            this.otherState = state;
        }
        return state;
    }

    public void recontextualize(BlockGetter level, Side side) {
        MixedSlabBlockEntity entity;
        BlockEntity blockEntity = level.getBlockEntity(this.pos);
        this.blockEntity = blockEntity instanceof MixedSlabBlockEntity ? (entity = (MixedSlabBlockEntity)blockEntity) : null;
        this.side = side;
    }

    public SlabContext flipContext(BlockGetter level) {
        this.recontextualize(level, this.side.flip());
        return this;
    }

    public BlockState rootState() {
        return this.rootState;
    }

    public BlockState mainState() {
        return this.state(this.side);
    }

    public BlockState otherState() {
        return this.state(this.side.flip());
    }

    public Block block(Side side) {
        return this.state(side).getBlock();
    }

    public Block rootBlock() {
        return this.rootState.getBlock();
    }

    public Block mainBlock() {
        return this.mainState().getBlock();
    }

    public Block otherBlock() {
        return this.otherState().getBlock();
    }

    public MixedHandler handler(Side side) {
        return MixedHandlers.getOrThrow(this.block(side));
    }

    public MixedHandler mainHandler() {
        return this.handler(this.side);
    }

    public MixedHandler otherHandler() {
        return this.handler(this.side.flip());
    }

    public boolean replaceMain(Level level, Block block) {
        return this.replaceSide(level, block, this.side);
    }

    public boolean replaceOther(Level level, Block block) {
        return this.replaceSide(level, block, this.side.flip());
    }

    public boolean replaceSide(Level level, Block block, Side side) {
        if (!Utility.isSlabWithVertical(block) && block != Blocks.AIR && block != Blocks.WATER) {
            return false;
        }
        boolean isTowards = side.isTowards();
        Block rootBlock = this.rootBlock();
        boolean waterlogged = this.rootState.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(this.rootState.is(Blocks.WATER));
        BlockState replacedState = this.state(side);
        BlockState keepState = this.state(side.flip()).setValue(BlockStateProperties.WATERLOGGED, waterlogged);
        if (this.rootState.isAir() || this.rootState.is(Blocks.WATER)) {
            if (!Utility.isSlabWithVertical(block)) {
                return false;
            }
            SlabBlock slab = VerticalSlabBlock.getRoot(block);
            BlockState state = this.type.state(slab, isTowards).setValue(BlockStateProperties.WATERLOGGED, waterlogged);
            boolean success = level.setBlock(this.pos, state, 3);
            if (!success) {
                return false;
            }
            this.rootState = state;
            this.otherState = null;
            this.mainState = null;
            return true;
        }
        if (block == Blocks.AIR || block == Blocks.WATER) {
            if (replacedState.isAir()) {
                return false;
            }
            BlockState state = keepState.isAir() && waterlogged != false ? Blocks.WATER.defaultBlockState() : keepState;
            boolean success = level.setBlock(this.pos, state, 3);
            if (!success) {
                return false;
            }
            this.rootState = state;
            this.blockEntity = null;
            this.otherState = null;
            this.mainState = null;
            return true;
        }
        if (rootBlock == SlabRegistry.MIXED_SLAB) {
            boolean success;
            if (this.blockEntity == null) {
                BlockEntity blockEntity = level.getBlockEntity(this.pos);
                if (!(blockEntity instanceof MixedSlabBlockEntity)) {
                    return false;
                }
                MixedSlabBlockEntity mixedEntity = (MixedSlabBlockEntity)blockEntity;
                this.blockEntity = mixedEntity;
            }
            if (!(success = this.blockEntity.setBlock(block, isTowards))) {
                return false;
            }
            this.otherState = null;
            this.mainState = null;
            return true;
        }
        if (replacedState.isAir() && keepState.is(block)) {
            BlockState rootState = SlabContext.doubleSlab(keepState);
            boolean success = level.setBlock(this.pos, rootState, 3);
            if (!success) {
                return false;
            }
            this.rootState = rootState;
            this.otherState = null;
            this.mainState = null;
            return true;
        }
        if (Utility.isDoubleSlab(this.rootState)) {
            if (block == rootBlock) {
                return false;
            }
            MixedSlabBlock.MixedType type = MixedSlabBlock.MixedType.fromState(this.rootState);
            BlockState rootState = SlabRegistry.MIXED_SLAB.defaultBlockState().setValue(MixedSlabBlock.TYPE, type);
            boolean success = level.setBlock(this.pos, rootState, 3);
            if (!success) {
                return false;
            }
            MixedSlabBlockEntity blockEntity = Objects.requireNonNull((MixedSlabBlockEntity)level.getBlockEntity(this.pos));
            success = blockEntity.setBlocks(isTowards ? block : rootBlock, isTowards ? rootBlock : block);
            if (!success) {
                return false;
            }
            this.blockEntity = blockEntity;
            this.rootState = rootState;
            this.otherState = null;
            this.mainState = null;
            return true;
        }
        return false;
    }

    public static BlockState doubleSlab(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof SlabBlock) {
            return state.setValue(BlockStateProperties.SLAB_TYPE, SlabType.DOUBLE);
        }
        if (block instanceof VerticalSlabBlock) {
            return state.setValue(VerticalSlabBlock.TYPE, VerticalSlabBlock.VerticalType.FULL);
        }
        return state;
    }

    public static enum Side {
        TOWARDS,
        AWAY;


        public boolean isTowards() {
            return this == TOWARDS;
        }

        public Side flip() {
            return this == TOWARDS ? AWAY : TOWARDS;
        }

        public static Side fromTowards(boolean isTowards) {
            return isTowards ? TOWARDS : AWAY;
        }
    }
}

