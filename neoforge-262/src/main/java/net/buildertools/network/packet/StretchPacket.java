package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Alt+drag stretch: the client sends the region as it was when the drag started (min/max) and the
 * region as it ended, plus the dragged axis. The server remaps the selection's blocks
 * proportionally along that axis (rubber-sheet style) to fill the new region.
 */
public record StretchPacket(int axis, boolean positive,
                            int xMin, int yMin, int zMin, int xMax, int yMax, int zMax,
                            int nxMin, int nyMin, int nzMin, int nxMax, int nyMax, int nzMax)
        implements CustomPacketPayload {
    public static final Type<StretchPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "stretch"));

    public static final StreamCodec<FriendlyByteBuf, StretchPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public StretchPacket decode(FriendlyByteBuf buf) {
            int axis = buf.readVarInt();
            boolean positive = buf.readBoolean();
            return new StretchPacket(axis, positive,
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, StretchPacket packet) {
            buf.writeVarInt(packet.axis());
            buf.writeBoolean(packet.positive());
            buf.writeVarInt(packet.xMin());
            buf.writeVarInt(packet.yMin());
            buf.writeVarInt(packet.zMin());
            buf.writeVarInt(packet.xMax());
            buf.writeVarInt(packet.yMax());
            buf.writeVarInt(packet.zMax());
            buf.writeVarInt(packet.nxMin());
            buf.writeVarInt(packet.nyMin());
            buf.writeVarInt(packet.nzMin());
            buf.writeVarInt(packet.nxMax());
            buf.writeVarInt(packet.nyMax());
            buf.writeVarInt(packet.nzMax());
        }
    };

    public static StretchPacket create(int axis, boolean positive,
                                       BlockPos origMin, BlockPos origMax, BlockPos newMin, BlockPos newMax) {
        return new StretchPacket(axis, positive,
                origMin.getX(), origMin.getY(), origMin.getZ(), origMax.getX(), origMax.getY(), origMax.getZ(),
                newMin.getX(), newMin.getY(), newMin.getZ(), newMax.getX(), newMax.getY(), newMax.getZ());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StretchPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
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
