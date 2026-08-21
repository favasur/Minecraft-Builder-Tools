package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: place a NEW rotated block into {@code cell} (the held block item, rotated by
 * yaw/pitch) or re-rotate the block already in that cell. The cell keeps its ORIGINAL vanilla
 * block - only the rotation layer changes.
 */
public record BlockRotationPacket(BlockPos cell, float yaw, float pitch, boolean billboard)
        implements CustomPacketPayload {
    public static final Type<BlockRotationPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "block_rotation"));

    public static final StreamCodec<FriendlyByteBuf, BlockRotationPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BlockRotationPacket decode(FriendlyByteBuf buf) {
            return new BlockRotationPacket(
                    buf.readBlockPos(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, BlockRotationPacket packet) {
            buf.writeBlockPos(packet.cell());
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            buf.writeBoolean(packet.billboard());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockRotationPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.handleBlockRotation(serverPlayer, payload.cell(),
                        payload.yaw(), payload.pitch(), payload.billboard());
            }
        });
    }
}
