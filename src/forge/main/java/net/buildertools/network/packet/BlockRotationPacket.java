package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Client -> Server: place a NEW rotated block at the exact model center ({@code cx},{@code cy},
 * {@code cz}) inside {@code cell} (the held block item, rotated by yaw/pitch) or re-rotate the
 * block already in that cell. The cell keeps its ORIGINAL vanilla block - only the rotation
 * layer changes. The center is fractional for blocks snapped onto a rotated neighbor's grid.
 */
public record BlockRotationPacket(BlockPos cell, double cx, double cy, double cz,
                                  float yaw, float pitch, boolean billboard) {
    public static BlockRotationPacket decode(FriendlyByteBuf buf) {
        return new BlockRotationPacket(
                buf.readBlockPos(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readBoolean());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(cell());
        buf.writeDouble(cx());
        buf.writeDouble(cy());
        buf.writeDouble(cz());
        buf.writeFloat(yaw());
        buf.writeFloat(pitch());
        buf.writeBoolean(billboard());
    }

    public static void handle(BlockRotationPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.handleBlockRotation(serverPlayer, payload.cell(),
                        payload.cx(), payload.cy(), payload.cz(),
                        payload.yaw(), payload.pitch(), payload.billboard());
            }
        });
    }
}
