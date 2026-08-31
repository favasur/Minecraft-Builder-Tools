package net.buildertools.network.packet;

import net.buildertools.server.RotationStore;
import net.buildertools.util.ArchBlockData;
import net.buildertools.util.EllipseBlockData;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Server -> Client: one rotated block of the mod's layer changed ({@code remove} = the block there
 * is gone). Carries the block's real state so the client can render it with full shading, collide
 * with it and show its rotation. Applied to the client mirror. Arch voussoirs carry their wedge
 * geometry ({@code arch}) instead of a plain rotation.
 */
public record RotationSyncPacket(BlockPos pos, BlockState state, float yaw, float pitch,
                                 boolean billboard, boolean remove,
                                 double cx, double cy, double cz,
                                 ArchBlockData arch, EllipseBlockData ellipse) {
    public static RotationSyncPacket decode(FriendlyByteBuf buf) {
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
                readEllipse(buf));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeInt(state() == null ? -1 : Block.BLOCK_STATE_REGISTRY.getId(state()));
        buf.writeBlockPos(pos());
        buf.writeFloat(yaw());
        buf.writeFloat(pitch());
        buf.writeBoolean(billboard());
        buf.writeBoolean(remove());
        buf.writeDouble(cx());
        buf.writeDouble(cy());
        buf.writeDouble(cz());
        writeArch(buf, arch());
        writeEllipse(buf, ellipse());
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

    public static void handle(RotationSyncPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            // Server -> client only: the sender is null on the receiving (client) side.
            if (ctx.getSender() == null) {
                RotationStore.applyClientSync(
                        payload.pos(),
                        payload.remove() ? null
                                : new RotationData(payload.state(), payload.yaw(), payload.pitch(), payload.billboard(),
                                        new Vec3(payload.cx(), payload.cy(), payload.cz()), payload.arch(), payload.ellipse()),
                        payload.remove());
            }
        });
    }
}
