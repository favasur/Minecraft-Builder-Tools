package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: smooth terrain heights across a disc around {@code center}.
 */
public record SmoothPacket(BlockPos center) {
    public static SmoothPacket decode(FriendlyByteBuf buf) {
return new SmoothPacket(buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeBlockPos(center());
    }

    public static void handle(SmoothPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.smooth(serverPlayer, payload.center());
            }
        });
    }
}
