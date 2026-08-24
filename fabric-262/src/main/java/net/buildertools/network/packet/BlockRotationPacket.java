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
 * Client -> Server: place a NEW rotated block at the exact model center ({@code cx},{@code cy},
 * {@code cz}) inside {@code cell} (the held block item, rotated by yaw/pitch) or re-rotate the
 * block already in that cell. The cell keeps its ORIGINAL vanilla block - only the rotation
 * layer changes. The center is fractional for blocks snapped onto a rotated neighbor's grid.
 */
public record BlockRotationPacket(BlockPos cell, double cx, double cy, double cz,
                                  float yaw, float pitch, boolean billboard)
        implements CustomPacketPayload {
    public static final Type<BlockRotationPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "block_rotation"));

    public static final StreamCodec<FriendlyByteBuf, BlockRotationPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BlockRotationPacket decode(FriendlyByteBuf buf) {
            return new BlockRotationPacket(
                    buf.readBlockPos(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, BlockRotationPacket packet) {
            buf.writeBlockPos(packet.cell());
            buf.writeDouble(packet.cx());
            buf.writeDouble(packet.cy());
            buf.writeDouble(packet.cz());
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            buf.writeBoolean(packet.billboard());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockRotationPacket payload, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        ctx.server().execute(() -> BuilderServerHandler.handleBlockRotation(
                player, payload.cell(), payload.cx(), payload.cy(), payload.cz(),
                payload.yaw(), payload.pitch(), payload.billboard()));
    }
}
