package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: undo the player's most recent fill or paste operation.
 */
public record UndoPacket() implements CustomPacketPayload {
    public static final Type<UndoPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "undo"));

    public static final StreamCodec<FriendlyByteBuf, UndoPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public UndoPacket decode(FriendlyByteBuf buf) {
            return new UndoPacket();
        }

        @Override
        public void encode(FriendlyByteBuf buf, UndoPacket packet) {
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UndoPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.undo(serverPlayer);
            }
        });
    }
}
