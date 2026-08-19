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
 * Client -> Server: creative settings for the world. Any field left at its "skip" sentinel is
 * not touched. {@code weather}: 0 = clear, 1 = rain, 2 = thunder, -1 = skip.
 */
public record WorldSettingsPacket(long timeOfDay, Boolean pauseTime, int weather)
        implements CustomPacketPayload {
    public static final long SKIP_TIME = -1;
    public static final int SKIP_WEATHER = -1;

    public static final Type<WorldSettingsPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "world_settings"));

    public static final StreamCodec<FriendlyByteBuf, WorldSettingsPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public WorldSettingsPacket decode(FriendlyByteBuf buf) {
            return new WorldSettingsPacket(
                    buf.readLong(),
                    buf.readBoolean() ? buf.readBoolean() : null,
                    buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, WorldSettingsPacket packet) {
            buf.writeLong(packet.timeOfDay());
            buf.writeBoolean(packet.pauseTime() != null);
            if (packet.pauseTime() != null) {
                buf.writeBoolean(packet.pauseTime());
            }
            buf.writeVarInt(packet.weather());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WorldSettingsPacket payload, CustomPayloadEvent.Context context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                BuilderServerHandler.applyWorldSettings(player,
                        payload.timeOfDay(), payload.pauseTime(), payload.weather());
            }
        });
    }
}
