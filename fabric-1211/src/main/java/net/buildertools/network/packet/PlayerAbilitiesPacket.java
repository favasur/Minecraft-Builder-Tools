package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: creative player abilities (flight speed multiplier, no-clip, flying).
 * Any field left at its "skip" sentinel is not touched.
 */
public record PlayerAbilitiesPacket(float flySpeed, Boolean noClip, Boolean fly)
        implements CustomPacketPayload {
    public static final float SKIP_SPEED = -1.0f;

    public static final Type<PlayerAbilitiesPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "player_abilities"));

    public static final StreamCodec<FriendlyByteBuf, PlayerAbilitiesPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PlayerAbilitiesPacket decode(FriendlyByteBuf buf) {
            return new PlayerAbilitiesPacket(
                    buf.readFloat(),
                    buf.readBoolean() ? buf.readBoolean() : null,
                    buf.readBoolean() ? buf.readBoolean() : null);
        }

        @Override
        public void encode(FriendlyByteBuf buf, PlayerAbilitiesPacket packet) {
            buf.writeFloat(packet.flySpeed());
            buf.writeBoolean(packet.noClip() != null);
            if (packet.noClip() != null) {
                buf.writeBoolean(packet.noClip());
            }
            buf.writeBoolean(packet.fly() != null);
            if (packet.fly() != null) {
                buf.writeBoolean(packet.fly());
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayerAbilitiesPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.applyPlayerAbilities(serverPlayer,
                        payload.flySpeed(), payload.noClip(), payload.fly());
            }
        });
    }
}
