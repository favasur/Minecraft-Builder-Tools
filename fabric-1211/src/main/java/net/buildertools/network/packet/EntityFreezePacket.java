package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: freeze or unfreeze a selected entity (stops its AI / keeps it in place).
 */
public record EntityFreezePacket(int entityId, boolean freeze) implements CustomPacketPayload {
    public static final Type<EntityFreezePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "entity_freeze"));

    public static final StreamCodec<FriendlyByteBuf, EntityFreezePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public EntityFreezePacket decode(FriendlyByteBuf buf) {
            return new EntityFreezePacket(buf.readVarInt(), buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, EntityFreezePacket packet) {
            buf.writeVarInt(packet.entityId());
            buf.writeBoolean(packet.freeze());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EntityFreezePacket payload, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        player.server.execute(() -> {
                BuilderServerHandler.freezeEntity(player, payload.entityId(), payload.freeze());
        });
    }
}
