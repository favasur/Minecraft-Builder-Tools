package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent;

/**
 * Client -> Server: arch the row of blocks inside the given region relative to the clicked block
 * face. The client sends the region as it ended up after the ALT+A stretch plus the cell and FACE
 * the player clicked to the side of the row; the server builds the local frame from that face
 * (the face plane holds the arch, the face normal is the depth) and replaces the row with tapered
 * voussoir wedges in the mod's block layer.
 */
public record ArchPacket(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax,
                         BlockPos click, byte face) {
    public static ArchPacket decode(FriendlyByteBuf buf) {
        return new ArchPacket(
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readBlockPos(), buf.readByte());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(xMin());
        buf.writeVarInt(yMin());
        buf.writeVarInt(zMin());
        buf.writeVarInt(xMax());
        buf.writeVarInt(yMax());
        buf.writeVarInt(zMax());
        buf.writeBlockPos(click());
        buf.writeByte(face());
    }

    public static ArchPacket create(BlockPos min, BlockPos max, BlockPos click, Direction face) {
        return new ArchPacket(min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ(), click, (byte) face.get3DDataValue());
    }

    public static void handle(ArchPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            Player player = ctx.getSender();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.archBlocks(serverPlayer,
                        new BlockPos(payload.xMin(), payload.yMin(), payload.zMin()),
                        new BlockPos(payload.xMax(), payload.yMax(), payload.zMax()),
                        payload.click(), Direction.from3DDataValue(payload.face()));
            }
        });
    }
}
