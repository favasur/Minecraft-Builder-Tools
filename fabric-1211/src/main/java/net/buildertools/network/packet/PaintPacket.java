package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: paint the held block into a sphere around {@code center}.
 */
public record PaintPacket(BlockPos center) implements CustomPacketPayload {
    public static final Type<PaintPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "paint"));

    public static final StreamCodec<FriendlyByteBuf, PaintPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PaintPacket decode(FriendlyByteBuf buf) {
            return new PaintPacket(buf.readBlockPos());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PaintPacket packet) {
            buf.writeBlockPos(packet.center());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PaintPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.paint(serverPlayer, payload.center());
            }
        });
    }
}
