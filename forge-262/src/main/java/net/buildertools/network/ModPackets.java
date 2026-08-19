package net.buildertools.network;

import net.buildertools.BuilderToolsMod;
import net.buildertools.network.packet.EntityDeletePacket;
import net.buildertools.network.packet.EntityDuplicatePacket;
import net.buildertools.network.packet.EntityFreezePacket;
import net.buildertools.network.packet.EntityTransformPacket;
import net.buildertools.network.packet.OffGridBlockPacket;
import net.buildertools.network.packet.PaintPacket;
import net.buildertools.network.packet.PastePacket;
import net.buildertools.network.packet.PlayerAbilitiesPacket;
import net.buildertools.network.packet.ScatterPacket;
import net.buildertools.network.packet.SelectionCopyPacket;
import net.buildertools.network.packet.SelectionFillPacket;
import net.buildertools.network.packet.SelectionSyncPacket;
import net.buildertools.network.packet.SmoothPacket;
import net.buildertools.network.packet.StretchPacket;
import net.buildertools.network.packet.UndoPacket;
import net.buildertools.network.packet.WorldSettingsPacket;
import net.minecraft.resources.Identifier;
import net.minecraftforge.network.Channel.VersionTest;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;

/**
 * Forge 26.2 networking: a single {@link SimpleChannel} carrying the builder-tools payloads.
 * The play protocol registers each packet per direction with its stream codec; handlers run on
 * the network thread and switch to the main thread via {@code CustomPayloadEvent.Context.enqueueWork}.
 */
public final class ModPackets {
    private static final int PROTOCOL_VERSION = 1;

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(BuilderToolsMod.MODID, "main"))
            .clientAcceptedVersions(VersionTest.exact(PROTOCOL_VERSION))
            .serverAcceptedVersions(VersionTest.exact(PROTOCOL_VERSION))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel()
            .play()
            .serverbound(flow -> {
                flow.add(SelectionFillPacket.class, SelectionFillPacket.STREAM_CODEC.cast(), SelectionFillPacket::handle);
                flow.add(SelectionCopyPacket.class, SelectionCopyPacket.STREAM_CODEC.cast(), SelectionCopyPacket::handle);
                flow.add(PastePacket.class, PastePacket.STREAM_CODEC.cast(), PastePacket::handle);
                flow.add(EntityTransformPacket.class, EntityTransformPacket.STREAM_CODEC.cast(), EntityTransformPacket::handle);
                flow.add(OffGridBlockPacket.class, OffGridBlockPacket.STREAM_CODEC.cast(), OffGridBlockPacket::handle);
                flow.add(EntityDeletePacket.class, EntityDeletePacket.STREAM_CODEC.cast(), EntityDeletePacket::handle);
                flow.add(EntityDuplicatePacket.class, EntityDuplicatePacket.STREAM_CODEC.cast(), EntityDuplicatePacket::handle);
                flow.add(PaintPacket.class, PaintPacket.STREAM_CODEC.cast(), PaintPacket::handle);
                flow.add(ScatterPacket.class, ScatterPacket.STREAM_CODEC.cast(), ScatterPacket::handle);
                flow.add(SmoothPacket.class, SmoothPacket.STREAM_CODEC.cast(), SmoothPacket::handle);
                flow.add(StretchPacket.class, StretchPacket.STREAM_CODEC.cast(), StretchPacket::handle);
                flow.add(UndoPacket.class, UndoPacket.STREAM_CODEC.cast(), UndoPacket::handle);
                flow.add(WorldSettingsPacket.class, WorldSettingsPacket.STREAM_CODEC.cast(), WorldSettingsPacket::handle);
                flow.add(PlayerAbilitiesPacket.class, PlayerAbilitiesPacket.STREAM_CODEC.cast(), PlayerAbilitiesPacket::handle);
                flow.add(EntityFreezePacket.class, EntityFreezePacket.STREAM_CODEC.cast(), EntityFreezePacket::handle);
                flow.add(SelectionSyncPacket.class, SelectionSyncPacket.STREAM_CODEC.cast(), SelectionSyncPacket::handleServer);
            })
            .clientbound()
            .add(SelectionSyncPacket.class, SelectionSyncPacket.STREAM_CODEC.cast(), SelectionSyncPacket::handleClient)
            .build();

    private ModPackets() {
    }

    /** No-op hook kept for symmetry with the other loaders; the channel builds eagerly. */
    public static void register() {
    }
}
