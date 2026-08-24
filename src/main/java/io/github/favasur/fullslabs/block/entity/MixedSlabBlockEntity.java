package io.github.favasur.fullslabs.block.entity;

import io.github.favasur.fullslabs.FullSlabs;
import io.github.favasur.fullslabs.SlabRegistry;
import io.github.favasur.fullslabs.block.MixedSlabBlock;
import io.github.favasur.fullslabs.block.VerticalSlabBlock;
import io.github.favasur.fullslabs.handlers.MixedHandlers;
import java.util.Objects;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Tuple;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

@MethodsReturnNonnullByDefault
public class MixedSlabBlockEntity
extends BlockEntity {
    private static Tuple<SlabBlock, SlabBlock> CACHE = new Tuple((Object)((SlabBlock)Blocks.STONE_SLAB), (Object)((SlabBlock)Blocks.STONE_SLAB));
    private SlabBlock towards;
    private SlabBlock away;

    public MixedSlabBlockEntity(BlockPos pos, BlockState state, SlabBlock towards, SlabBlock away) {
        super((BlockEntityType)SlabRegistry.MIXED_SLAB_ENTITY.get(), pos, state);
        this.towards = towards;
        this.away = away;
    }

    public MixedSlabBlockEntity(BlockPos pos, BlockState state) {
        this(pos, state, (SlabBlock)Blocks.STONE_SLAB, (SlabBlock)Blocks.STONE_SLAB);
    }

    public BlockState getTowardsState() {
        return ((MixedSlabBlock.MixedType)((Object)this.getBlockState().getValue(MixedSlabBlock.TYPE))).state(this.towards, true);
    }

    public BlockState getAwayState() {
        return ((MixedSlabBlock.MixedType)((Object)this.getBlockState().getValue(MixedSlabBlock.TYPE))).state(this.away, false);
    }

    public BlockState getState(boolean towards) {
        return ((MixedSlabBlock.MixedType)((Object)this.getBlockState().getValue(MixedSlabBlock.TYPE))).state(towards ? this.towards : this.away, towards);
    }

    public BlockState getTargetedState(BlockHitResult crosshair) {
        return this.getTargetedState(crosshair.getBlockPos(), crosshair.getLocation());
    }

    public BlockState getTargetedState(BlockPos location, Vec3 pos) {
        MixedSlabBlock.MixedType type;
        boolean towards = (type = (MixedSlabBlock.MixedType)((Object)this.getBlockState().getValue(MixedSlabBlock.TYPE))).isAxisTargetTowards(pos, location);
        return type.state(towards ? this.towards : this.away, towards);
    }

    public SlabBlock getTowards() {
        return this.towards;
    }

    public SlabBlock getAway() {
        return this.away;
    }

    public SlabBlock getBlock(boolean towards) {
        return towards ? this.towards : this.away;
    }

    public boolean setTowards(Block slab) {
        return this.setBlock(slab, true);
    }

    public boolean setAway(Block slab) {
        return this.setBlock(slab, false);
    }

    public boolean setBlock(Block block, boolean towards) {
        SlabBlock slab = VerticalSlabBlock.getRoot(block);
        if (!MixedHandlers.hasHandler((Block)slab)) {
            return false;
        }
        if (towards) {
            this.towards = slab;
        } else {
            this.away = slab;
        }
        this.setChanged();
        this.syncModel();
        return true;
    }

    public boolean setBlocks(Block towards, Block away) {
        SlabBlock towardsRoot = VerticalSlabBlock.getRoot(towards);
        if (!MixedHandlers.hasHandler((Block)towardsRoot)) {
            return false;
        }
        SlabBlock awayRoot = VerticalSlabBlock.getRoot(away);
        if (!MixedHandlers.hasHandler((Block)awayRoot)) {
            return false;
        }
        this.towards = towardsRoot;
        this.away = awayRoot;
        this.setChanged();
        this.syncModel();
        return true;
    }

    public SlabBlock getTargetedSlab(BlockHitResult crosshair) {
        MixedSlabBlock.MixedType type = (MixedSlabBlock.MixedType)((Object)this.getBlockState().getValue(MixedSlabBlock.TYPE));
        return this.getBlock(type.isAxisTargetTowards(crosshair.getLocation(), crosshair.getBlockPos()));
    }

    @ApiStatus.Internal
    public static void writeCache(SlabBlock towards, SlabBlock away) {
        CACHE = new Tuple((Object)towards, (Object)away);
    }

    @ApiStatus.Internal
    public void readCache() {
        this.towards = (SlabBlock)CACHE.getA();
        this.away = (SlabBlock)CACHE.getB();
    }

    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("towards_id", BuiltInRegistries.BLOCK.getKey(this.towards).toString());
        tag.putString("away_id", BuiltInRegistries.BLOCK.getKey(this.away).toString());
    }

    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        String towardsStr = tag.getString("towards_id");
        String awayStr = tag.getString("away_id");
        this.towards = loadSlab(towardsStr);
        this.away = loadSlab(awayStr);
    }

    private static SlabBlock loadSlab(String id) {
        if (id != null && !id.isEmpty()) {
            Object object = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
            if (object instanceof SlabBlock slab) {
                return slab;
            }
            FullSlabs.LOGGER.warn("missing \"{}\": replacing with \"minecraft:stone_slab\"", id);
        }
        return (SlabBlock) Blocks.STONE_SLAB;
    }

    @Nullable
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create((BlockEntity)this);
    }

    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    public void syncModel() {
        Objects.requireNonNull(this.level).blockEvent(this.worldPosition, this.getBlockState().getBlock(), 0, 0);
    }

    public record ModelContext(int towards, int away) {
        public static ModelContext fromStates(BlockState towards, BlockState away) {
            return new ModelContext(Block.getId((BlockState)towards), Block.getId((BlockState)away));
        }

        public BlockState towardsState() {
            return Block.stateById((int)this.towards);
        }

        public BlockState awayState() {
            return Block.stateById((int)this.away);
        }
    }
}

