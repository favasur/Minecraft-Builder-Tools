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
 * Client -> Server: scatter the held block across surfaces in a sphere around {@code center}.
 */
public record ScatterPacket(BlockPos center) implements CustomPacketPayload {
    public static final Type<ScatterPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "scatter"));

    public static final StreamCodec<FriendlyByteBuf, ScatterPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ScatterPacket decode(FriendlyByteBuf buf) {
            return new ScatterPacket(buf.readBlockPos());
        }

        @Override
        public void encode(FriendlyByteBuf buf, ScatterPacket packet) {
            buf.writeBlockPos(packet.center());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ScatterPacket payload, Context context) {
        context.enqueueWork(() -> {
            ServerPlayer serverPlayer = context.getSender();
            if (serverPlayer != null) {
                BuilderServerHandler.scatter(serverPlayer, payload.center());
            }
        });
    }
}
