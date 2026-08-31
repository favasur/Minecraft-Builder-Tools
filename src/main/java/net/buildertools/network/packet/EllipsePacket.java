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
 * Client -> Server: turn the placed region into a closed elliptical ring of voussoirs relative to
 * the clicked block face (the ALT+E mechanic). The clicked face's plane holds the ring - the
 * region's projected extents along the face's in-plane axes become the semi-axes
 * {@code a}/{@code b} (the outer edge of the ring sits flush with the region's faces), the region
 * center is the ellipse center, and the region's thickness along the face normal is the ring's
 * depth (each depth cell becomes a concentric ring layer). The server replaces the region's
 * vanilla blocks with tapered voussoir wedges in the mod's block layer.
 */
public record EllipsePacket(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax,
                            BlockPos click, byte face)
        implements CustomPacketPayload {
    public static final Type<EllipsePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "ellipse"));

    public static final StreamCodec<FriendlyByteBuf, EllipsePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public EllipsePacket decode(FriendlyByteBuf buf) {
            return new EllipsePacket(
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBlockPos(), buf.readByte());
        }

        @Override
        public void encode(FriendlyByteBuf buf, EllipsePacket packet) {
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

    public static EllipsePacket create(BlockPos min, BlockPos max, BlockPos click, Direction face) {
        return new EllipsePacket(min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ(), click, (byte) face.get3DDataValue());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EllipsePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (player instanceof ServerPlayer serverPlayer) {
                BuilderServerHandler.ellipseBlocks(serverPlayer,
                        new BlockPos(payload.xMin(), payload.yMin(), payload.zMin()),
                        new BlockPos(payload.xMax(), payload.yMax(), payload.zMax()),
                        payload.click(), Direction.from3DDataValue(payload.face()));
            }
        });
    }
}
