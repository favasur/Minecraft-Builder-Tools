package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: paste the player's clipboard so that its min corner lands on {@code anchor}.
 */
public record PastePacket(BlockPos anchor) {
    public static PastePacket decode(FriendlyByteBuf buf) {
return new PastePacket(buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeBlockPos(anchor());
    }

    public static void handle(PastePacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.paste(serverPlayer, payload.anchor());
            }
        });
    }
}
