package io.github.favasur.fullslabs.block;

import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.WeatheringCopperSlabBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

@MethodsReturnNonnullByDefault
public class OxidizableVerticalSlabBlock
extends VerticalSlabBlock
implements WeatheringCopper {
    private final WeatheringCopper.WeatherState oxidationLevel;

    public OxidizableVerticalSlabBlock(WeatheringCopperSlabBlock block, BlockBehaviour.Properties properties) {
        super((SlabBlock)block, properties);
        this.oxidationLevel = block.getAge();
    }

    protected void randomTick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        this.changeOverTime(state, world, pos, random);
    }

    protected boolean isRandomlyTicking(BlockState state) {
        return WeatheringCopper.getNext((Block)this.parent).isPresent();
    }

    public WeatheringCopper.WeatherState getAge() {
        return this.oxidationLevel;
    }
}

