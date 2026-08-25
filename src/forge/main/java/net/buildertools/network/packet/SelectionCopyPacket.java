package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: copy the given region into the player's clipboard.
 */
public record SelectionCopyPacket(BlockPos corner1, BlockPos corner2) {
    public static SelectionCopyPacket decode(FriendlyByteBuf buf) {
return new SelectionCopyPacket(buf.readBlockPos(), buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeBlockPos(corner1());
buf.writeBlockPos(corner2());
    }

    public static void handle(SelectionCopyPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.copySelection(serverPlayer, payload.corner1(), payload.corner2());
            }
        });
    }
}
