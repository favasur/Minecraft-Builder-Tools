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
 * Client -> Server: fill the given region with the block held in the player's main hand.
 */
public record SelectionFillPacket(BlockPos corner1, BlockPos corner2) implements CustomPacketPayload {
    public static final Type<SelectionFillPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "fill_selection"));

    public static final StreamCodec<FriendlyByteBuf, SelectionFillPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SelectionFillPacket decode(FriendlyByteBuf buf) {
            return new SelectionFillPacket(buf.readBlockPos(), buf.readBlockPos());
        }

        @Override
        public void encode(FriendlyByteBuf buf, SelectionFillPacket packet) {
            buf.writeBlockPos(packet.corner1());
            buf.writeBlockPos(packet.corner2());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SelectionFillPacket payload, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        player.server.execute(() -> {
                BuilderServerHandler.fillSelection(player, payload.corner1(), payload.corner2());
        });
    }
}
