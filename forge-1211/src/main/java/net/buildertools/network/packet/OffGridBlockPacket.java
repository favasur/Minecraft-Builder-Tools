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
public record OffGridBlockPacket(int x, int y, int z, float yaw, float pitch, boolean remove) {
    public static OffGridBlockPacket decode(FriendlyByteBuf buf) {
        return new OffGridBlockPacket(
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(x());
        buf.writeVarInt(y());
        buf.writeVarInt(z());
        buf.writeFloat(yaw());
        buf.writeFloat(pitch());
        buf.writeBoolean(remove());
    }

    public static void handle(OffGridBlockPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                if (payload.remove()) {
                    BuilderServerHandler.removeOffGrid(serverPlayer, payload.x(), payload.y(), payload.z());
                } else {
                    BuilderServerHandler.placeOffGrid(serverPlayer, payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch());
                }
            }
        });
    }
}
