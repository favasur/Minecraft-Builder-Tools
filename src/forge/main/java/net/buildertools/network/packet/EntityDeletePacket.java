package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: remove an entity.
 */
public record EntityDeletePacket(int entityId) {
    public static EntityDeletePacket decode(FriendlyByteBuf buf) {
return new EntityDeletePacket(buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeVarInt(entityId());
    }

    public static void handle(EntityDeletePacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.deleteEntity(serverPlayer, payload.entityId());
            }
        });
    }
}
