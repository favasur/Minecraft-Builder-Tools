package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: arch the row of blocks inside the given region relative to the clicked block
 * face. The client sends the region as it ended up after the ALT+A stretch plus the cell and
 * FACE the player clicked to the side of the row; the server builds the local frame from that
 * face (the face plane holds the arch, the face normal is the depth) and replaces the row with
 * tapered voussoir wedges in the mod's block layer.
 */
public record ArchPacket(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax,
                         BlockPos click, byte face)
        implements CustomPacketPayload {
    public static final Type<ArchPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "arch"));

    public static final StreamCodec<FriendlyByteBuf, ArchPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ArchPacket decode(FriendlyByteBuf buf) {
            return new ArchPacket(
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBlockPos(), buf.readByte());
        }

        @Override
        public void encode(FriendlyByteBuf buf, ArchPacket packet) {
            buf.writeVarInt(packet.xMin());
            buf.writeVarInt(packet.yMin());
            buf.writeVarInt(packet.zMin());
            buf.writeVarInt(packet.xMax());
            buf.writeVarInt(packet.yMax());
            buf.writeVarInt(packet.zMax());
            buf.writeBlockPos(packet.click());
            buf.writeByte(packet.face());
        }
    };

    public static ArchPacket create(BlockPos min, BlockPos max, BlockPos click, Direction face) {
        return new ArchPacket(min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ(), click, (byte) face.get3DDataValue());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ArchPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.archBlocks(serverPlayer,
                        new BlockPos(payload.xMin(), payload.yMin(), payload.zMin()),
                        new BlockPos(payload.xMax(), payload.yMax(), payload.zMax()),
                        payload.click(), Direction.from3DDataValue(payload.face()));
            }
        });
    }
}
