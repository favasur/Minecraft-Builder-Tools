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
 * Client -> Server: remove an entity.
 */
public record EntityDeletePacket(int entityId) implements CustomPacketPayload {
    public static final Type<EntityDeletePacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "entity_delete"));

    public static final StreamCodec<FriendlyByteBuf, EntityDeletePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public EntityDeletePacket decode(FriendlyByteBuf buf) {
            return new EntityDeletePacket(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, EntityDeletePacket packet) {
            buf.writeVarInt(packet.entityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EntityDeletePacket payload, CustomPayloadEvent.Context context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                BuilderServerHandler.deleteEntity(player, payload.entityId());
            }
        });
    }
}
