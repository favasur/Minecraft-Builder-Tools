package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent.Context;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: undo the player's most recent fill or paste operation.
 */
public record UndoPacket() implements CustomPacketPayload {
    public static final Type<UndoPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "undo"));

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

    public static void handle(UndoPacket payload, Context context) {
        context.enqueueWork(() -> {
            ServerPlayer serverPlayer = context.getSender();
            if (serverPlayer != null) {
                BuilderServerHandler.undo(serverPlayer);
            }
        });
    }
}
