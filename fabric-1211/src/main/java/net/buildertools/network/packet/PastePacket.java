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
 * Client -> Server: paste the player's clipboard so that its min corner lands on {@code anchor}.
 */
public record PastePacket(BlockPos anchor) implements CustomPacketPayload {
    public static final Type<PastePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "paste"));

    public static final StreamCodec<FriendlyByteBuf, PastePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PastePacket decode(FriendlyByteBuf buf) {
            return new PastePacket(buf.readBlockPos());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PastePacket packet) {
            buf.writeBlockPos(packet.anchor());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PastePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.paste(serverPlayer, payload.anchor());
            }
        });
    }
}
