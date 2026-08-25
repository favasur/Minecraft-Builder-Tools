package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: undo the player's most recent fill or paste operation.
 */
public record UndoPacket() {
    public static UndoPacket decode(FriendlyByteBuf buf) {
return new UndoPacket();
    }

    public void encode(FriendlyByteBuf buf) {

    }

    public static void handle(UndoPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.undo(serverPlayer);
            }
        });
    }
}
