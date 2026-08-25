package net.buildertools.server;

import net.buildertools.util.RotationData;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * The mod's rotation layer for a dimension: which cells have a rotated player-placed block and
 * its yaw/pitch. The block in each cell remains the original vanilla block - this data is the
 * "neighbor-dependent grid" the rotation lives in. Persisted with the world automatically.
 */
public class RotationSavedData extends SavedData {
    public static final String NAME = "buildertools_rotations";

    private final Map<BlockPos, RotationData> rotations = new HashMap<>();

    public static RotationSavedData of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RotationSavedData::new, RotationSavedData::load, null), NAME);
    }

    public RotationData get(BlockPos pos) {
        return this.rotations.get(pos);
    }

    public void set(BlockPos pos, RotationData data) {
        this.rotations.put(pos, data);
        setDirty();
    }

    public boolean remove(BlockPos pos) {
        RotationData removed = this.rotations.remove(pos);
        if (removed != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public Map<BlockPos, RotationData> all() {
        return this.rotations;
    }

    public static RotationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RotationSavedData data = new RotationSavedData();
        ListTag list = tag.getList("rotations", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long[] pos = entry.getLongArray("pos");
            if (pos.length != 3) {
                continue;
            }
            BlockState state = Blocks.AIR.defaultBlockState();
            if (entry.contains("state")) {
                try {
                    state = BlockStateParser.parseForBlock(
                            registries.lookupOrThrow(Registries.BLOCK),
                            entry.getString("state"), false).blockState();
                } catch (Exception ignored) {
                }
            }
            Vec3 center = null;
            if (entry.contains("cx") && entry.contains("cy") && entry.contains("cz")) {
                center = new Vec3(entry.getDouble("cx"), entry.getDouble("cy"), entry.getDouble("cz"));
            }
            data.rotations.put(new BlockPos((int) pos[0], (int) pos[1], (int) pos[2]),
                    new RotationData(state, entry.getFloat("yaw"), entry.getFloat("pitch"),
                            entry.getBoolean("billboard"), center));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, RotationData> e : this.rotations.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLongArray("pos", new long[]{e.getKey().getX(), e.getKey().getY(), e.getKey().getZ()});
            entry.putString("state", BlockStateParser.serialize(e.getValue().state()));
            entry.putFloat("yaw", e.getValue().yaw());
            entry.putFloat("pitch", e.getValue().pitch());
            entry.putBoolean("billboard", e.getValue().billboard());
            Vec3 c = e.getValue().center(e.getKey());
            entry.putDouble("cx", c.x);
            entry.putDouble("cy", c.y);
            entry.putDouble("cz", c.z);
            list.add(entry);
        }
        tag.put("rotations", list);
        return tag;
    }
}
