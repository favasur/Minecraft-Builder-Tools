package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent.Context;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: copy the given region into the player's clipboard.
 */
public record SelectionCopyPacket(BlockPos corner1, BlockPos corner2) implements CustomPacketPayload {
    public static final Type<SelectionCopyPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "copy_selection"));

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

    public static void handle(SelectionCopyPacket payload, Context context) {
        context.enqueueWork(() -> {
            ServerPlayer serverPlayer = context.getSender();
            if (serverPlayer != null) {
                BuilderServerHandler.copySelection(serverPlayer, payload.corner1(), payload.corner2());
            }
        });
    }
}
