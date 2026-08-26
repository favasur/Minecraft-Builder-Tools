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
 * Client -> Server: duplicate an entity (full NBT copy, new UUID).
 */
public record EntityDuplicatePacket(int entityId) implements CustomPacketPayload {
    public static final Type<EntityDuplicatePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "entity_duplicate"));

    public static final StreamCodec<FriendlyByteBuf, EntityDuplicatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public EntityDuplicatePacket decode(FriendlyByteBuf buf) {
            return new EntityDuplicatePacket(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, EntityDuplicatePacket packet) {
            buf.writeVarInt(packet.entityId());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EntityDuplicatePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.duplicateEntity(serverPlayer, payload.entityId());
            }
        });
    }
}
