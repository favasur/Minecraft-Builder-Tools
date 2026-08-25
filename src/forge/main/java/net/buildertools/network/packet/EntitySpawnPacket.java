package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Client -> Server: spawn an entity of the given type at the position (the Entity Tool's E
 * interface). The server validates the distance and type before spawning.
 */
public record EntitySpawnPacket(ResourceLocation entityType, double x, double y, double z) {
    public static EntitySpawnPacket decode(FriendlyByteBuf buf) {
        return new EntitySpawnPacket(
                buf.readResourceLocation(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(entityType());
        buf.writeDouble(x());
        buf.writeDouble(y());
        buf.writeDouble(z());
    }

    public static void handle(EntitySpawnPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.spawnEntity(serverPlayer, payload.entityType(),
                        payload.x(), payload.y(), payload.z());
            }
        });
    }
}
