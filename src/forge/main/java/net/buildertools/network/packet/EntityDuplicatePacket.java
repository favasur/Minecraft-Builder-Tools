package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: duplicate an entity (full NBT copy, new UUID).
 */
public record EntityDuplicatePacket(int entityId) {
    public static EntityDuplicatePacket decode(FriendlyByteBuf buf) {
return new EntityDuplicatePacket(buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeVarInt(entityId());
    }

    public static void handle(EntityDuplicatePacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.duplicateEntity(serverPlayer, payload.entityId());
            }
        });
    }
}
