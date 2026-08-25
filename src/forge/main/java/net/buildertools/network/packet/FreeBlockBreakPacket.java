package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Client -> Server: break the rotated block in the given cell of the mod's layer (drops its item
 * in survival, like breaking a normal block).
 */
public record FreeBlockBreakPacket(BlockPos cell) {
    public static FreeBlockBreakPacket decode(FriendlyByteBuf buf) {
        return new FreeBlockBreakPacket(buf.readBlockPos());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cell());
    }

    public static void handle(FreeBlockBreakPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.handleFreeBlockBreak(serverPlayer, payload.cell());
            }
        });
    }
}
