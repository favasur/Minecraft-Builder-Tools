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
 * Client -> Server: absolutely reposition (and optionally re-rotate) an entity. When
 * {@code headOnly} is set, only the head yaw is changed (Hytale's Alt+R "rotate head" mode).
 */
public record EntityTransformPacket(int entityId, double x, double y, double z, float yaw, float pitch, boolean headOnly)
        implements CustomPacketPayload {
    public static final Type<EntityTransformPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "entity_transform"));

    public static final StreamCodec<FriendlyByteBuf, EntityTransformPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public EntityTransformPacket decode(FriendlyByteBuf buf) {
            return new EntityTransformPacket(
                    buf.readVarInt(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, EntityTransformPacket packet) {
            buf.writeVarInt(packet.entityId());
            buf.writeDouble(packet.x());
            buf.writeDouble(packet.y());
            buf.writeDouble(packet.z());
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            buf.writeBoolean(packet.headOnly());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EntityTransformPacket payload, CustomPayloadEvent.Context context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                BuilderServerHandler.moveEntity(player, payload.entityId(),
                        payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch(), payload.headOnly());
            }
        });
    }
}
