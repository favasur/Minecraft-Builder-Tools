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
 * Client -> Server: copy the given region into the player's clipboard.
 */
public record SelectionCopyPacket(BlockPos corner1, BlockPos corner2) implements CustomPacketPayload {
    public static final Type<SelectionCopyPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "copy_selection"));

    public static final StreamCodec<FriendlyByteBuf, SelectionCopyPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SelectionCopyPacket decode(FriendlyByteBuf buf) {
            return new SelectionCopyPacket(buf.readBlockPos(), buf.readBlockPos());
        }

        @Override
        public void encode(FriendlyByteBuf buf, SelectionCopyPacket packet) {
            buf.writeBlockPos(packet.corner1());
            buf.writeBlockPos(packet.corner2());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SelectionCopyPacket payload, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        player.server.execute(() -> {
                BuilderServerHandler.copySelection(player, payload.corner1(), payload.corner2());
        });
    }
}
