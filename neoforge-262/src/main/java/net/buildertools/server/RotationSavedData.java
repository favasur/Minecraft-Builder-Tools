package net.buildertools.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side persistence for the mod's rotated-block layer, keyed by the level's data storage.
 * The layer is a map of cell -> rotation entry; 26.2 persists saved data via a
 * {@link SavedDataType} with a {@link Codec}, so the map is serialized as a list of
 * {@code {pos, state, yaw, pitch, billboard}} entries.
 */
public class RotationSavedData extends SavedData {
    private final Map<BlockPos, RotationData> map = new HashMap<>();

    /** One layer entry serialized as {pos, state, yaw, pitch, billboard, center}. */
    private record Entry(BlockPos pos, BlockState state, float yaw, float pitch, boolean billboard, Vec3 center) {
        private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                BlockState.CODEC.fieldOf("state").forGetter(Entry::state),
                Codec.FLOAT.fieldOf("yaw").forGetter(Entry::yaw),
                Codec.FLOAT.fieldOf("pitch").forGetter(Entry::pitch),
                Codec.BOOL.fieldOf("billboard").forGetter(Entry::billboard),
                // Optional: entries saved before the center existed have none (cell-centered).
                Vec3.CODEC.optionalFieldOf("center", null).forGetter(Entry::center)
        ).apply(instance, Entry::new));
    }

    private static final Codec<RotationSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.list(Entry.CODEC).fieldOf("rotations").forGetter(RotationSavedData::entries)
    ).apply(instance, RotationSavedData::fromEntries));

    public static final SavedDataType<RotationSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("buildertools", "rotations"),
            RotationSavedData::new, CODEC, DataFixTypes.SAVED_DATA_MAP_DATA);

    private static List<Entry> entries(RotationSavedData data) {
        List<Entry> list = new ArrayList<>();
        for (Map.Entry<BlockPos, RotationData> e : data.map.entrySet()) {
            list.add(new Entry(e.getKey(), e.getValue().state(), e.getValue().yaw(),
                    e.getValue().pitch(), e.getValue().billboard(), e.getValue().center(e.getKey())));
        }
        return list;
    }

    private static RotationSavedData fromEntries(List<Entry> entries) {
        RotationSavedData data = new RotationSavedData();
        for (Entry e : entries) {
            data.map.put(e.pos, new RotationData(e.state, e.yaw, e.pitch, e.billboard, e.center));
        }
        return data;
    }

    public static RotationSavedData of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** The rotated block in the cell, or null. */
    public RotationData get(BlockPos pos) {
        return map.get(pos);
    }

    public void set(BlockPos pos, RotationData data) {
        map.put(pos.immutable(), data);
        setDirty();
    }

    /** Removes the cell's entry; returns true when there was one. */
    public boolean remove(BlockPos pos) {
        boolean removed = map.remove(pos.immutable()) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    /** A live view of the whole layer (server side). */
    public Map<BlockPos, RotationData> all() {
        return map;
    }
}
