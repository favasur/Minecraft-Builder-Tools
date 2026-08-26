package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: place, re-rotate or remove an off-grid (rotated) block. The position is the
 * block model's world-space CENTER (fractional - flush-adjacent blocks in a rotated stratum have
 * fractional centers), so {@code remove} matches the block whose center is closest to it, and
 * placement spawns the model centered there.
 */
public record OffGridBlockPacket(double cx, double cy, double cz, float yaw, float pitch, boolean remove,
                                 boolean billboard)
        implements CustomPacketPayload {
    public static final Type<OffGridBlockPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "offgrid_block"));

    public static final StreamCodec<FriendlyByteBuf, OffGridBlockPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OffGridBlockPacket decode(FriendlyByteBuf buf) {
            return new OffGridBlockPacket(
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean(),
                    buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, OffGridBlockPacket packet) {
            buf.writeDouble(packet.cx());
            buf.writeDouble(packet.cy());
            buf.writeDouble(packet.cz());
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            buf.writeBoolean(packet.remove());
            buf.writeBoolean(packet.billboard());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OffGridBlockPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                if (payload.remove()) {
                    BuilderServerHandler.removeOffGrid(serverPlayer, payload.cx(), payload.cy(), payload.cz());
                } else {
                    BuilderServerHandler.placeOffGrid(serverPlayer, payload.cx(), payload.cy(), payload.cz(),
                            payload.yaw(), payload.pitch(), payload.billboard());
                }
            }
        });
    }
}
