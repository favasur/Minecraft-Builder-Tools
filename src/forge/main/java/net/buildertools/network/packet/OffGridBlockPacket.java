package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Client -> Server: place or remove an off-grid (rotated) block. When {@code remove} is false the
 * block display is spawned at the cell with the given yaw; when true the display in that cell is
 * removed (and its item dropped).
 */
public record OffGridBlockPacket(double cx, double cy, double cz, float yaw, float pitch, boolean remove,
                                 boolean billboard) {
    public static OffGridBlockPacket decode(FriendlyByteBuf buf) {
        return new OffGridBlockPacket(
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(cx());
        buf.writeDouble(cy());
        buf.writeDouble(cz());
        buf.writeFloat(yaw());
        buf.writeFloat(pitch());
        buf.writeBoolean(remove());
        buf.writeBoolean(billboard());
    }

    public static void handle(OffGridBlockPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                if (payload.remove()) {
                    BuilderServerHandler.removeOffGrid(serverPlayer, payload.cx(), payload.cy(), payload.cz());
                } else {
                    BuilderServerHandler.placeOffGrid(serverPlayer, payload.cx(), payload.cy(), payload.cz(), payload.yaw(), payload.pitch(), payload.billboard());
                }
            }
        });
    }
}
