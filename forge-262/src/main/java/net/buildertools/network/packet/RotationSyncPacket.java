package net.buildertools.network.packet;

import net.buildertools.server.RotationStore;
import net.buildertools.util.ArchBlockData;
import net.buildertools.util.BezierBlockData;
import net.buildertools.util.EllipseBlockData;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.network.CustomPayloadEvent.Context;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Server -> Client: one rotated block of the mod's layer changed ({@code remove} = the block there
 * is gone). Carries the block's real state so the client can render it with full shading, collide
 * with it and show its rotation. Applied to the client mirror. Arch voussoirs carry their wedge
 * geometry ({@code arch}) instead of a plain rotation.
 */
public record RotationSyncPacket(BlockPos pos, BlockState state, float yaw, float pitch,
                                 boolean billboard, boolean remove,
                                 double cx, double cy, double cz,
                                 ArchBlockData arch, EllipseBlockData ellipse,
                                 BezierBlockData bezier)
        implements CustomPacketPayload {
    public static final Type<RotationSyncPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "rotation_sync"));

    public static final StreamCodec<FriendlyByteBuf, RotationSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RotationSyncPacket decode(FriendlyByteBuf buf) {
            int stateId = buf.readInt();
            BlockState state = stateId < 0 ? null : Block.BLOCK_STATE_REGISTRY.byId(stateId);
            return new RotationSyncPacket(
                    buf.readBlockPos(),
                    state,
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    readArch(buf),
                    readEllipse(buf),
                    readBezier(buf));
        }

        @Override
        public void encode(FriendlyByteBuf buf, RotationSyncPacket packet) {
            buf.writeInt(packet.state() == null ? -1 : Block.BLOCK_STATE_REGISTRY.getId(packet.state()));
            buf.writeBlockPos(packet.pos());
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            buf.writeBoolean(packet.billboard());
            buf.writeBoolean(packet.remove());
            buf.writeDouble(packet.cx());
            buf.writeDouble(packet.cy());
            buf.writeDouble(packet.cz());
            writeArch(buf, packet.arch());
            writeEllipse(buf, packet.ellipse());
            writeBezier(buf, packet.bezier());
        }
    };

    private static void writeBezier(FriendlyByteBuf buf, BezierBlockData bezier) {
        if (bezier == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeDouble(bezier.ax());
        buf.writeDouble(bezier.ay());
        buf.writeDouble(bezier.az());
        buf.writeDouble(bezier.cx());
        buf.writeDouble(bezier.cy());
        buf.writeDouble(bezier.cz());
        buf.writeDouble(bezier.bx());
        buf.writeDouble(bezier.by());
        buf.writeDouble(bezier.bz());
        buf.writeDouble(bezier.vx());
        buf.writeDouble(bezier.vy());
        buf.writeDouble(bezier.vz());
        buf.writeDouble(bezier.t0());
        buf.writeDouble(bezier.t1());
    }

    private static BezierBlockData readBezier(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        return new BezierBlockData(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble());
    }

    private static void writeEllipse(FriendlyByteBuf buf, EllipseBlockData ellipse) {
        if (ellipse == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeDouble(ellipse.cx());
        buf.writeDouble(ellipse.cy());
        buf.writeDouble(ellipse.cz());
        buf.writeDouble(ellipse.ux());
        buf.writeDouble(ellipse.uy());
        buf.writeDouble(ellipse.uz());
        buf.writeDouble(ellipse.wx());
        buf.writeDouble(ellipse.wy());
        buf.writeDouble(ellipse.wz());
        buf.writeDouble(ellipse.a());
        buf.writeDouble(ellipse.b());
        buf.writeDouble(ellipse.thetaStart());
        buf.writeDouble(ellipse.deltaTheta());
    }

    private static EllipseBlockData readEllipse(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        return new EllipseBlockData(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble());
    }

    private static void writeArch(FriendlyByteBuf buf, ArchBlockData arch) {
        if (arch == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeDouble(arch.ox());
        buf.writeDouble(arch.oy());
        buf.writeDouble(arch.oz());
        buf.writeDouble(arch.ux());
        buf.writeDouble(arch.uy());
        buf.writeDouble(arch.uz());
        buf.writeDouble(arch.wx());
        buf.writeDouble(arch.wy());
        buf.writeDouble(arch.wz());
        buf.writeDouble(arch.thetaStart());
        buf.writeDouble(arch.deltaTheta());
        buf.writeDouble(arch.radius());
    }

    private static ArchBlockData readArch(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return null;
        }
        return new ArchBlockData(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RotationSyncPacket payload, Context context) {
        context.enqueueWork(() -> RotationStore.applyClientSync(
                payload.pos(),
                payload.remove() ? null
                        : new RotationData(payload.state(), payload.yaw(), payload.pitch(), payload.billboard(),
                                new Vec3(payload.cx(), payload.cy(), payload.cz()), payload.arch(), payload.ellipse(),
                                payload.bezier()),
                payload.remove()));
    }
}
