package net.buildertools.network;

import net.buildertools.BuilderToolsMod;
import net.buildertools.network.packet.BlockRotationPacket;
import net.buildertools.network.packet.EntityDeletePacket;
import net.buildertools.network.packet.EntityDuplicatePacket;
import net.buildertools.network.packet.EntityFreezePacket;
import net.buildertools.network.packet.EntitySpawnPacket;
import net.buildertools.network.packet.EntityTransformPacket;
import net.buildertools.network.packet.FreeBlockBreakPacket;
import net.buildertools.network.packet.OffGridBlockPacket;
import net.buildertools.network.packet.RotationSyncPacket;
import net.buildertools.network.packet.PlayerAbilitiesPacket;
import net.buildertools.network.packet.PaintPacket;
import net.buildertools.network.packet.PastePacket;
import net.buildertools.network.packet.ScatterPacket;
import net.buildertools.network.packet.SelectionCopyPacket;
import net.buildertools.network.packet.SelectionFillPacket;
import net.buildertools.network.packet.SelectionSyncPacket;
import net.buildertools.network.packet.SmoothPacket;
import net.buildertools.network.packet.SmoothTerrainTogglePacket;
import net.buildertools.network.packet.StretchPacket;
import net.buildertools.network.packet.UndoPacket;
import net.buildertools.network.packet.WorldSettingsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;

/**
 * Forge 65.1.1 networking: builds the mod's payload channel ({@code buildertools:main}) through
 * the typed {@code ChannelBuilder} API. Each payload is declared on the serverbound, clientbound
 * or bidirectional play flow with its stream codec (widened from {@code FriendlyByteBuf} to the
 * registry-aware play buffer via {@link net.minecraft.network.codec.StreamCodec#cast()}) and its
 * handler method. The built channel also wraps payloads for {@code Connection.send(Packet)}
 * (Forge 26.2 removed the payload overload).
 */
public class ModPackets {
    /** The mod's payload channel; also used to wrap payloads when sending. */
    public static final Channel<CustomPacketPayload> CHANNEL = buildChannel();

    private static Channel<CustomPacketPayload> buildChannel() {
        var play = ChannelBuilder.named(Identifier.fromNamespaceAndPath(BuilderToolsMod.MODID, "main"))
                .networkProtocolVersion(1)
                .optional()
                .payloadChannel()
                .play();

        play.serverbound(flow -> {
            flow.add(SelectionFillPacket.TYPE, SelectionFillPacket.STREAM_CODEC.cast(), SelectionFillPacket::handle);
            flow.add(SelectionCopyPacket.TYPE, SelectionCopyPacket.STREAM_CODEC.cast(), SelectionCopyPacket::handle);
            flow.add(PastePacket.TYPE, PastePacket.STREAM_CODEC.cast(), PastePacket::handle);
            flow.add(EntityTransformPacket.TYPE, EntityTransformPacket.STREAM_CODEC.cast(), EntityTransformPacket::handle);
            flow.add(EntitySpawnPacket.TYPE, EntitySpawnPacket.STREAM_CODEC.cast(), EntitySpawnPacket::handle);
            flow.add(EntityDeletePacket.TYPE, EntityDeletePacket.STREAM_CODEC.cast(), EntityDeletePacket::handle);
            flow.add(EntityDuplicatePacket.TYPE, EntityDuplicatePacket.STREAM_CODEC.cast(), EntityDuplicatePacket::handle);
            flow.add(PaintPacket.TYPE, PaintPacket.STREAM_CODEC.cast(), PaintPacket::handle);
            flow.add(ScatterPacket.TYPE, ScatterPacket.STREAM_CODEC.cast(), ScatterPacket::handle);
            flow.add(SmoothPacket.TYPE, SmoothPacket.STREAM_CODEC.cast(), SmoothPacket::handle);
            flow.add(StretchPacket.TYPE, StretchPacket.STREAM_CODEC.cast(), StretchPacket::handle);
            flow.add(UndoPacket.TYPE, UndoPacket.STREAM_CODEC.cast(), UndoPacket::handle);
            flow.add(WorldSettingsPacket.TYPE, WorldSettingsPacket.STREAM_CODEC.cast(), WorldSettingsPacket::handle);
            flow.add(PlayerAbilitiesPacket.TYPE, PlayerAbilitiesPacket.STREAM_CODEC.cast(), PlayerAbilitiesPacket::handle);
            flow.add(EntityFreezePacket.TYPE, EntityFreezePacket.STREAM_CODEC.cast(), EntityFreezePacket::handle);
            flow.add(OffGridBlockPacket.TYPE, OffGridBlockPacket.STREAM_CODEC.cast(), OffGridBlockPacket::handle);
            flow.add(BlockRotationPacket.TYPE, BlockRotationPacket.STREAM_CODEC.cast(), BlockRotationPacket::handle);
            flow.add(FreeBlockBreakPacket.TYPE, FreeBlockBreakPacket.STREAM_CODEC.cast(), FreeBlockBreakPacket::handle);
        });

        play.clientbound(flow -> {
            flow.add(RotationSyncPacket.TYPE, RotationSyncPacket.STREAM_CODEC.cast(), RotationSyncPacket::handle);
            flow.add(SmoothTerrainTogglePacket.TYPE, SmoothTerrainTogglePacket.STREAM_CODEC.cast(), SmoothTerrainTogglePacket::handle);
        });

        // Two-way payload (client -> server keeps the command store in sync, server -> client
        // applies expand/contract/shift). The handler routes on the packet direction itself.
        var bidirectional = play.bidirectional();
        bidirectional.add(SelectionSyncPacket.TYPE, SelectionSyncPacket.STREAM_CODEC.cast(), SelectionSyncPacket::handle);
        return bidirectional.build();
    }

    /** Client -> server: sends a payload through the mod's channel over the active connection. */
    public static void sendToServer(CustomPacketPayload payload) {
        var listener = Minecraft.getInstance().getConnection();
        if (listener != null) {
            CHANNEL.send(payload, listener.getConnection());
        }
    }

    /** Server -> client: sends a payload through the mod's channel to one connection. */
    public static void sendToClient(Connection connection, CustomPacketPayload payload) {
        CHANNEL.send(payload, connection);
    }
}
