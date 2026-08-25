package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: creative settings for the world. Any field left at its "skip" sentinel is
 * not touched. {@code weather}: 0 = clear, 1 = rain, 2 = thunder, -1 = skip.
 */
public record WorldSettingsPacket(long timeOfDay, Boolean pauseTime, int weather)
        {
    public static final long SKIP_TIME = -1;
    public static final int SKIP_WEATHER = -1;

    public static WorldSettingsPacket decode(FriendlyByteBuf buf) {
return new WorldSettingsPacket(
        buf.readLong(),
        buf.readBoolean() ? buf.readBoolean() : null,
        buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeLong(timeOfDay());
        buf.writeBoolean(pauseTime() != null);
        if (pauseTime() != null) {
            buf.writeBoolean(pauseTime());
        }
        buf.writeVarInt(weather());
    }

    public static void handle(WorldSettingsPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.applyWorldSettings(serverPlayer,
                        payload.timeOfDay(), payload.pauseTime(), payload.weather());
            }
        });
    }
}
