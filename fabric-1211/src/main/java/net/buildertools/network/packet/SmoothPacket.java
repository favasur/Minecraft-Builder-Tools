package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: smooth terrain heights across a disc around {@code center}.
 */
public record SmoothPacket(BlockPos center) implements CustomPacketPayload {
    public static final Type<SmoothPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "smooth"));

    public static final StreamCodec<FriendlyByteBuf, SmoothPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SmoothPacket decode(FriendlyByteBuf buf) {
            return new SmoothPacket(buf.readBlockPos());
        }

        @Override
        public void encode(FriendlyByteBuf buf, SmoothPacket packet) {
            buf.writeBlockPos(packet.center());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SmoothPacket payload, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        player.server.execute(() -> {
                BuilderServerHandler.smooth(player, payload.center());
        });
    }
}
