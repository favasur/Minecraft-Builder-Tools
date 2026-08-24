package net.buildertools.network.packet;

import net.buildertools.server.RotationStore;
import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.network.CustomPayloadEvent;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Server -> Client: one rotated block of the mod's layer changed ({@code remove} = the block there
 * is gone). Carries the block's real state so the client can render it, collide with it and show
 * its rotation. Applied to the client mirror.
 */
public record RotationSyncPacket(BlockPos pos, BlockState state, float yaw, float pitch,
                                 boolean billboard, boolean remove,
                                 double cx, double cy, double cz) implements CustomPacketPayload {
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
                    buf.readDouble());
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
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RotationSyncPacket payload, CustomPayloadEvent.Context context) {
        context.enqueueWork(() -> {
            // Server -> client only: the receiving side has no server player.
            if (context.getSender() == null) {
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
