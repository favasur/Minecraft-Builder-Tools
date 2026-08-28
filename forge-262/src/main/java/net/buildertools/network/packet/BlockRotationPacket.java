package net.buildertools.network.packet;

import net.buildertools.server.BuilderServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent.Context;
import org.jetbrains.annotations.Nullable;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Client -> Server: place a NEW rotated block at the exact model center ({@code cx},{@code cy},
 * {@code cz}) inside {@code cell} (the held block item, rotated by yaw/pitch) or re-rotate the
 * block already in that cell. The cell keeps its ORIGINAL vanilla block - only the rotation
 * layer changes. The center is fractional for blocks snapped onto a rotated neighbor's grid.
 *
 * <p>{@code slabDirection} carries the placement direction for slab clicks against rotated
 * blocks ({@code RotatedSlabPlacement}): UP/DOWN lay horizontal top/bottom slabs, a horizontal
 * direction stands a vertical slab occupying that half. Null for ordinary block clicks.
 *
 * <p>{@code mergeDouble} converts the rotated slab already in {@code cell} into a full double
 * slab in place (same-material inner-face click, mirroring the vanilla merge rule); the angles
 * and the exact model center stay.
 */
public record BlockRotationPacket(BlockPos cell, double cx, double cy, double cz,
                                  float yaw, float pitch, boolean billboard,
                                  @Nullable Direction slabDirection, boolean mergeDouble)
        implements CustomPacketPayload {
    public static final Type<BlockRotationPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath(MODID, "block_rotation"));

    public static final StreamCodec<FriendlyByteBuf, BlockRotationPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BlockRotationPacket decode(FriendlyByteBuf buf) {
            int dir = buf.readByte();
            return new BlockRotationPacket(
                    buf.readBlockPos(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readDouble(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readBoolean(),
                    dir < 0 ? null : Direction.values()[dir],
                    buf.readBoolean());
        }

        @Override
        public void encode(FriendlyByteBuf buf, BlockRotationPacket packet) {
            buf.writeByte(packet.slabDirection() == null ? -1 : packet.slabDirection().ordinal());
            buf.writeBlockPos(packet.cell());
            buf.writeDouble(packet.cx());
            buf.writeDouble(packet.cy());
            buf.writeDouble(packet.cz());
            buf.writeFloat(packet.yaw());
            buf.writeFloat(packet.pitch());
            buf.writeBoolean(packet.billboard());
            buf.writeBoolean(packet.mergeDouble());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BlockRotationPacket payload, Context context) {
        context.enqueueWork(() -> {
            ServerPlayer serverPlayer = context.getSender();
            if (serverPlayer != null) {
                BuilderServerHandler.handleBlockRotation(serverPlayer, payload.cell(),
                        payload.cx(), payload.cy(), payload.cz(),
                        payload.yaw(), payload.pitch(), payload.billboard(),
                        payload.slabDirection(), payload.mergeDouble());
            }
        });
    }
}
