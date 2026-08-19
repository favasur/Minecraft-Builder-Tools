package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: freeze or unfreeze a selected entity (stops its AI / keeps it in place).
 */
public record EntityFreezePacket(int entityId, boolean freeze) {
    public static EntityFreezePacket decode(FriendlyByteBuf buf) {
return new EntityFreezePacket(buf.readVarInt(), buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeVarInt(entityId());
buf.writeBoolean(freeze());
    }

    public static void handle(EntityFreezePacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.freezeEntity(serverPlayer, payload.entityId(), payload.freeze());
            }
        });
    }
}
