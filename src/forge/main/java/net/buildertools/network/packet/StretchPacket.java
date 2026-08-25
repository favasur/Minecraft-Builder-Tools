package net.buildertools.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.event.network.CustomPayloadEvent;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;


/**
 * Alt+drag stretch: the client sends the region as it was when the drag started (min/max) and the
 * region as it ended, plus the dragged axis. The server remaps the selection's blocks
 * proportionally along that axis (rubber-sheet style) to fill the new region.
 */
public record StretchPacket(int axis, boolean positive,
                            int xMin, int yMin, int zMin, int xMax, int yMax, int zMax,
                            int nxMin, int nyMin, int nzMin, int nxMax, int nyMax, int nzMax)
        {
    public static StretchPacket decode(FriendlyByteBuf buf) {
int axis = buf.readVarInt();
boolean positive = buf.readBoolean();
return new StretchPacket(axis, positive,
        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
        buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
buf.writeVarInt(axis());
buf.writeBoolean(positive());
buf.writeVarInt(xMin());
buf.writeVarInt(yMin());
buf.writeVarInt(zMin());
buf.writeVarInt(xMax());
buf.writeVarInt(yMax());
buf.writeVarInt(zMax());
buf.writeVarInt(nxMin());
buf.writeVarInt(nyMin());
buf.writeVarInt(nzMin());
buf.writeVarInt(nxMax());
buf.writeVarInt(nyMax());
buf.writeVarInt(nzMax());
    }

    public static StretchPacket create(int axis, boolean positive,
                                       BlockPos origMin, BlockPos origMax, BlockPos newMin, BlockPos newMax) {
        return new StretchPacket(axis, positive,
                origMin.getX(), origMin.getY(), origMin.getZ(), origMax.getX(), origMax.getY(), origMax.getZ(),
                newMin.getX(), newMin.getY(), newMin.getZ(), newMax.getX(), newMax.getY(), newMax.getZ());
    }

    public static void handle(StretchPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.stretchSelection(serverPlayer, payload.axis(), payload.positive(),
                        new BlockPos(payload.xMin(), payload.yMin(), payload.zMin()),
                        new BlockPos(payload.xMax(), payload.yMax(), payload.zMax()),
                        new BlockPos(payload.nxMin(), payload.nyMin(), payload.nzMin()),
                        new BlockPos(payload.nxMax(), payload.nyMax(), payload.nzMax()));
            }
        });
    }
}
