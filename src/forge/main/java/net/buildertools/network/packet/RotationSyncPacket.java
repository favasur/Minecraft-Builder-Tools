package net.buildertools.network.packet;

import net.buildertools.server.RotationStore;
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
 * with it and show its rotation. Applied to the client mirror.
 */
public record RotationSyncPacket(BlockPos pos, BlockState state, float yaw, float pitch,
                                 boolean billboard, boolean remove,
                                 double cx, double cy, double cz) {
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
                buf.readDouble());
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
    }

    public static void handle(RotationSyncPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            // Server -> client only: the sender is null on the receiving (client) side.
            if (ctx.getSender() == null) {
                RotationStore.applyClientSync(
                        payload.pos(),
                        payload.remove() ? null
                                : new RotationData(payload.state(), payload.yaw(), payload.pitch(), payload.billboard(),
                                        new Vec3(payload.cx(), payload.cy(), payload.cz())),
                        payload.remove());
            }
        });
    }
}
