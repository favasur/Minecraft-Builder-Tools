package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: absolutely reposition (and optionally re-rotate) an entity. When
 * {@code headOnly} is set, only the head yaw is changed (Hytale's Alt+R "rotate head" mode).
 */
public record EntityTransformPacket(int entityId, double x, double y, double z, float yaw, float pitch, boolean headOnly)
        {
    public static EntityTransformPacket decode(FriendlyByteBuf buf) {
return new EntityTransformPacket(
        buf.readVarInt(),
        buf.readDouble(),
        buf.readDouble(),
        buf.readDouble(),
        buf.readFloat(),
        buf.readFloat(),
        buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeVarInt(entityId());
buf.writeDouble(x());
buf.writeDouble(y());
buf.writeDouble(z());
buf.writeFloat(yaw());
buf.writeFloat(pitch());
buf.writeBoolean(headOnly());
    }

    public static void handle(EntityTransformPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.moveEntity(serverPlayer, payload.entityId(),
                        payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch(), payload.headOnly());
            }
        });
    }
}
