package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Client -> Server: scatter the held block across surfaces in a sphere around {@code center}.
 */
public record ScatterPacket(BlockPos center) {
    public static ScatterPacket decode(FriendlyByteBuf buf) {
return new ScatterPacket(buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeBlockPos(center());
    }

    public static void handle(ScatterPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.scatter(serverPlayer, payload.center());
            }
        });
    }
}
