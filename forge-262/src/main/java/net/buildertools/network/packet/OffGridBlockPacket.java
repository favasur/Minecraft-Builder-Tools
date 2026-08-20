package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: place or remove an off-grid (rotated) block. When {@code remove} is false the
 * block display is spawned at the cell with the given yaw; when true the display in that cell is
 * removed (and its item dropped).
 */
public record OffGridBlockPacket(double cx, double cy, double cz, float yaw, float pitch, boolean remove, boolean billboard)
        implements CustomPacketPayload {
    public static final Type<OffGridBlockPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "offgrid_block"));

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

    public static void handle(OffGridBlockPacket payload, CustomPayloadEvent.Context context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                if (payload.remove()) {
                    BuilderServerHandler.removeOffGrid(player, payload.cx(), payload.cy(), payload.cz());
                } else {
                    BuilderServerHandler.placeOffGrid(player, payload.cx(), payload.cy(), payload.cz(), payload.yaw(), payload.pitch(), payload.billboard());
                }
            }
        });
    }
}
