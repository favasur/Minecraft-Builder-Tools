package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: place or remove an off-grid (rotated) block. When {@code remove} is false the
 * block display is spawned at the cell with the given yaw; when true the display in that cell is
 * removed (and its item dropped).
 */
public record OffGridBlockPacket(int x, int y, int z, float yaw, float pitch, boolean remove)
        implements CustomPacketPayload {
    public static final Type<OffGridBlockPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "offgrid_block"));

    public static final StreamCodec<FriendlyByteBuf, OffGridBlockPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OffGridBlockPacket decode(FriendlyByteBuf buf) {
            return new OffGridBlockPacket(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, OffGridBlockPacket packet) {
            buf.writeVarInt(packet.x());
            buf.writeVarInt(packet.y());
            buf.writeVarInt(packet.z());
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            buf.writeBoolean(packet.remove());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OffGridBlockPacket payload, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        player.server.execute(() -> {
            if (payload.remove()) {
                BuilderServerHandler.removeOffGrid(player, payload.x(), payload.y(), payload.z());
            } else {
                BuilderServerHandler.placeOffGrid(player, payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch());
            }
        });
    }
}
