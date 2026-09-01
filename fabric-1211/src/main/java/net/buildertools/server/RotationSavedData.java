package net.buildertools.server;

import net.buildertools.util.ArchBlockData;
import net.buildertools.util.BezierBlockData;
import net.buildertools.util.EllipseBlockData;
import net.buildertools.util.RotationData;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
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
            ArchBlockData arch = null;
            if (entry.contains("arch", Tag.TAG_LIST)) {
                ListTag a = entry.getList("arch", Tag.TAG_DOUBLE);
                if (a.size() >= 12) {
                    arch = new ArchBlockData(
                            a.getDouble(0), a.getDouble(1), a.getDouble(2),
                            a.getDouble(3), a.getDouble(4), a.getDouble(5),
                            a.getDouble(6), a.getDouble(7), a.getDouble(8),
                            a.getDouble(9), a.getDouble(10), a.getDouble(11));
                }
            }
            EllipseBlockData ellipse = null;
            if (entry.contains("ellipse", Tag.TAG_LIST)) {
                ListTag e = entry.getList("ellipse", Tag.TAG_DOUBLE);
                if (e.size() >= 13) {
                    ellipse = new EllipseBlockData(
                            e.getDouble(0), e.getDouble(1), e.getDouble(2),
                            e.getDouble(3), e.getDouble(4), e.getDouble(5),
                            e.getDouble(6), e.getDouble(7), e.getDouble(8),
                            e.getDouble(9), e.getDouble(10),
                            e.getDouble(11), e.getDouble(12));
                }
            }
            BezierBlockData bezier = null;
            if (entry.contains("bezier", Tag.TAG_LIST)) {
                ListTag bz = entry.getList("bezier", Tag.TAG_DOUBLE);
                if (bz.size() >= 14) {
                    bezier = new BezierBlockData(
                            bz.getDouble(0), bz.getDouble(1), bz.getDouble(2),
                            bz.getDouble(3), bz.getDouble(4), bz.getDouble(5),
                            bz.getDouble(6), bz.getDouble(7), bz.getDouble(8),
                            bz.getDouble(9), bz.getDouble(10), bz.getDouble(11),
                            bz.getDouble(12), bz.getDouble(13));
                }
            }
            data.rotations.put(new BlockPos((int) pos[0], (int) pos[1], (int) pos[2]),
                    new RotationData(state, entry.getFloat("yaw"), entry.getFloat("pitch"),
                            entry.getBoolean("billboard"), center, arch, ellipse, bezier));
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
            ArchBlockData arch = e.getValue().arch();
            if (arch != null) {
                ListTag a = new ListTag();
                a.add(DoubleTag.valueOf(arch.ox()));
                a.add(DoubleTag.valueOf(arch.oy()));
                a.add(DoubleTag.valueOf(arch.oz()));
                a.add(DoubleTag.valueOf(arch.ux()));
                a.add(DoubleTag.valueOf(arch.uy()));
                a.add(DoubleTag.valueOf(arch.uz()));
                a.add(DoubleTag.valueOf(arch.wx()));
                a.add(DoubleTag.valueOf(arch.wy()));
                a.add(DoubleTag.valueOf(arch.wz()));
                a.add(DoubleTag.valueOf(arch.thetaStart()));
                a.add(DoubleTag.valueOf(arch.deltaTheta()));
                a.add(DoubleTag.valueOf(arch.radius()));
                entry.put("arch", a);
            }
            BezierBlockData bezier = e.getValue().bezier();
            if (bezier != null) {
                ListTag bz = new ListTag();
                bz.add(DoubleTag.valueOf(bezier.ax()));
                bz.add(DoubleTag.valueOf(bezier.ay()));
                bz.add(DoubleTag.valueOf(bezier.az()));
                bz.add(DoubleTag.valueOf(bezier.cx()));
                bz.add(DoubleTag.valueOf(bezier.cy()));
                bz.add(DoubleTag.valueOf(bezier.cz()));
                bz.add(DoubleTag.valueOf(bezier.bx()));
                bz.add(DoubleTag.valueOf(bezier.by()));
                bz.add(DoubleTag.valueOf(bezier.bz()));
                bz.add(DoubleTag.valueOf(bezier.vx()));
                bz.add(DoubleTag.valueOf(bezier.vy()));
                bz.add(DoubleTag.valueOf(bezier.vz()));
                bz.add(DoubleTag.valueOf(bezier.t0()));
                bz.add(DoubleTag.valueOf(bezier.t1()));
                entry.put("bezier", bz);
            }
            EllipseBlockData ellipse = e.getValue().ellipse();
            if (ellipse != null) {
                ListTag el = new ListTag();
                el.add(DoubleTag.valueOf(ellipse.cx()));
                el.add(DoubleTag.valueOf(ellipse.cy()));
                el.add(DoubleTag.valueOf(ellipse.cz()));
                el.add(DoubleTag.valueOf(ellipse.ux()));
                el.add(DoubleTag.valueOf(ellipse.uy()));
                el.add(DoubleTag.valueOf(ellipse.uz()));
                el.add(DoubleTag.valueOf(ellipse.wx()));
                el.add(DoubleTag.valueOf(ellipse.wy()));
                el.add(DoubleTag.valueOf(ellipse.wz()));
                el.add(DoubleTag.valueOf(ellipse.a()));
                el.add(DoubleTag.valueOf(ellipse.b()));
                el.add(DoubleTag.valueOf(ellipse.thetaStart()));
                el.add(DoubleTag.valueOf(ellipse.deltaTheta()));
                entry.put("ellipse", el);
            }
            list.add(entry);
        }
        tag.put("rotations", list);
        return tag;
    }
}
