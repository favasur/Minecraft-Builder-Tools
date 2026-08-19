package net.buildertools.network;

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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ModPackets {
    private ModPackets() {
    }

    /** Called from the common entry point. Registers codecs + global receivers. */
    public static void register() {
        // Client -> server codecs.
        PayloadTypeRegistry.serverboundPlay().register(SelectionFillPacket.TYPE, SelectionFillPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SelectionCopyPacket.TYPE, SelectionCopyPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PastePacket.TYPE, PastePacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EntityTransformPacket.TYPE, EntityTransformPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(OffGridBlockPacket.TYPE, OffGridBlockPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EntityDeletePacket.TYPE, EntityDeletePacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EntityDuplicatePacket.TYPE, EntityDuplicatePacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PaintPacket.TYPE, PaintPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ScatterPacket.TYPE, ScatterPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SmoothPacket.TYPE, SmoothPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StretchPacket.TYPE, StretchPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UndoPacket.TYPE, UndoPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(WorldSettingsPacket.TYPE, WorldSettingsPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PlayerAbilitiesPacket.TYPE, PlayerAbilitiesPacket.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(EntityFreezePacket.TYPE, EntityFreezePacket.STREAM_CODEC);

        // Server -> client codecs (selection sync after expand/contract/shift).
        PayloadTypeRegistry.clientboundPlay().register(SelectionSyncPacket.TYPE, SelectionSyncPacket.STREAM_CODEC);

        // Server receivers.
        ServerPlayNetworking.registerGlobalReceiver(SelectionFillPacket.TYPE, SelectionFillPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(SelectionCopyPacket.TYPE, SelectionCopyPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(PastePacket.TYPE, PastePacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(EntityTransformPacket.TYPE, EntityTransformPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(OffGridBlockPacket.TYPE, OffGridBlockPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(EntityDeletePacket.TYPE, EntityDeletePacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(EntityDuplicatePacket.TYPE, EntityDuplicatePacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(PaintPacket.TYPE, PaintPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(ScatterPacket.TYPE, ScatterPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(SmoothPacket.TYPE, SmoothPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(StretchPacket.TYPE, StretchPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(UndoPacket.TYPE, UndoPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(WorldSettingsPacket.TYPE, WorldSettingsPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(PlayerAbilitiesPacket.TYPE, PlayerAbilitiesPacket::handle);
        ServerPlayNetworking.registerGlobalReceiver(EntityFreezePacket.TYPE, EntityFreezePacket::handle);
        // Two-way: client -> server keeps the command store in sync, server -> client applies
        // expand/contract/shift.
        ServerPlayNetworking.registerGlobalReceiver(SelectionSyncPacket.TYPE, SelectionSyncPacket::handleServer);
        ClientPlayNetworking.registerGlobalReceiver(SelectionSyncPacket.TYPE, SelectionSyncPacket::handleClient);
    }
}
