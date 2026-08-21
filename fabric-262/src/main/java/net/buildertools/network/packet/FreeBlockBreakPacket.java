package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: break the rotated block in the given cell of the mod's layer (drops its item
 * in survival, like breaking a normal block).
 */
public record FreeBlockBreakPacket(BlockPos cell) implements CustomPacketPayload {
    public static final Type<FreeBlockBreakPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "free_block_break"));

    public static final StreamCodec<FriendlyByteBuf, FreeBlockBreakPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public FreeBlockBreakPacket decode(FriendlyByteBuf buf) {
            return new FreeBlockBreakPacket(buf.readBlockPos());
        }

        @Override
        public void encode(FriendlyByteBuf buf, FreeBlockBreakPacket packet) {
            buf.writeBlockPos(packet.cell());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FreeBlockBreakPacket payload, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        ctx.server().execute(() -> BuilderServerHandler.handleFreeBlockBreak(player, payload.cell()));
    }
}
