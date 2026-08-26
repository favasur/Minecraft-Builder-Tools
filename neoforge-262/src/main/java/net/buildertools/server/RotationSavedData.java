package net.buildertools.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The mod's rotation layer for a dimension: which cells have a rotated player-placed block and
 * its yaw/pitch. The block in each cell remains the original vanilla block - this data is the
 * "neighbor-dependent grid" the rotation lives in. Persisted with the world automatically.
 */
public class RotationSavedData extends SavedData {
    public static final String NAME = "buildertools_rotations";

    private static final Codec<RotationData> ENTRY_CODEC = RecordCodecBuilder.create(i -> i.group(
            BlockState.CODEC.fieldOf("state").forGetter(RotationData::state),
            Codec.FLOAT.fieldOf("yaw").forGetter(RotationData::yaw),
            Codec.FLOAT.fieldOf("pitch").forGetter(RotationData::pitch),
            Codec.BOOL.fieldOf("billboard").forGetter(RotationData::billboard),
            Vec3.CODEC.optionalFieldOf("center").forGetter(d -> Optional.ofNullable(d.center()))
    ).apply(i, (state, yaw, pitch, billboard, center) ->
            new RotationData(state, yaw, pitch, billboard, center.orElse(null))));

    private static final Codec<Map.Entry<BlockPos, RotationData>> ENTRY_MAP_CODEC =
            RecordCodecBuilder.create(i -> i.group(
                    BlockPos.CODEC.fieldOf("pos").forGetter(Map.Entry::getKey),
                    ENTRY_CODEC.fieldOf("data").forGetter(Map.Entry::getValue)
            ).apply(i, (pos, data) -> Map.entry(pos, data)));

    public static final Codec<RotationSavedData> CODEC = Codec.list(ENTRY_MAP_CODEC)
            .xmap(entries -> {
                Map<BlockPos, RotationData> map = new HashMap<>();
                for (Map.Entry<BlockPos, RotationData> entry : entries) {
                    map.put(entry.getKey(), entry.getValue());
                }
                return new RotationSavedData(map);
            }, data -> data.rotations.entrySet().stream().toList());

    public static final SavedDataType<RotationSavedData> TYPE = new SavedDataType<>(
            Identifier.parse("buildertools:rotations"), RotationSavedData::new, CODEC);

    private final Map<BlockPos, RotationData> rotations;

    public RotationSavedData() {
        this(new HashMap<>());
    }

    public RotationSavedData(Map<BlockPos, RotationData> rotations) {
        this.rotations = rotations;
    }

    public static RotationSavedData of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
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
}
