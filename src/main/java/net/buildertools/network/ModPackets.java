package net.buildertools.network;

import net.buildertools.BuilderToolsMod;
import net.buildertools.network.packet.ArchPacket;
import net.buildertools.network.packet.BlockRotationPacket;
import net.buildertools.network.packet.EntityDeletePacket;
import net.buildertools.network.packet.EntityDuplicatePacket;
import net.buildertools.network.packet.EntityFreezePacket;
import net.buildertools.network.packet.EntitySpawnPacket;
import net.buildertools.network.packet.EntityTransformPacket;
import net.buildertools.network.packet.EllipsePacket;
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
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModPackets {
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(BuilderToolsMod.MODID).versioned("1").optional();

        registrar.playToServer(SelectionFillPacket.TYPE, SelectionFillPacket.STREAM_CODEC, SelectionFillPacket::handle);
        registrar.playToServer(SelectionCopyPacket.TYPE, SelectionCopyPacket.STREAM_CODEC, SelectionCopyPacket::handle);
        registrar.playToServer(PastePacket.TYPE, PastePacket.STREAM_CODEC, PastePacket::handle);
        registrar.playToServer(EntityTransformPacket.TYPE, EntityTransformPacket.STREAM_CODEC, EntityTransformPacket::handle);
        registrar.playToServer(EntitySpawnPacket.TYPE, EntitySpawnPacket.STREAM_CODEC, EntitySpawnPacket::handle);
        registrar.playToServer(EntityDeletePacket.TYPE, EntityDeletePacket.STREAM_CODEC, EntityDeletePacket::handle);
        registrar.playToServer(EntityDuplicatePacket.TYPE, EntityDuplicatePacket.STREAM_CODEC, EntityDuplicatePacket::handle);
        registrar.playToServer(PaintPacket.TYPE, PaintPacket.STREAM_CODEC, PaintPacket::handle);
        registrar.playToServer(ScatterPacket.TYPE, ScatterPacket.STREAM_CODEC, ScatterPacket::handle);
        registrar.playToServer(SmoothPacket.TYPE, SmoothPacket.STREAM_CODEC, SmoothPacket::handle);
        registrar.playToServer(StretchPacket.TYPE, StretchPacket.STREAM_CODEC, StretchPacket::handle);
        registrar.playToServer(UndoPacket.TYPE, UndoPacket.STREAM_CODEC, UndoPacket::handle);
        registrar.playToServer(WorldSettingsPacket.TYPE, WorldSettingsPacket.STREAM_CODEC, WorldSettingsPacket::handle);
        registrar.playToServer(PlayerAbilitiesPacket.TYPE, PlayerAbilitiesPacket.STREAM_CODEC, PlayerAbilitiesPacket::handle);
        registrar.playToServer(EntityFreezePacket.TYPE, EntityFreezePacket.STREAM_CODEC, EntityFreezePacket::handle);
        registrar.playToServer(OffGridBlockPacket.TYPE, OffGridBlockPacket.STREAM_CODEC, OffGridBlockPacket::handle);
        registrar.playToServer(BlockRotationPacket.TYPE, BlockRotationPacket.STREAM_CODEC, BlockRotationPacket::handle);
        registrar.playToServer(FreeBlockBreakPacket.TYPE, FreeBlockBreakPacket.STREAM_CODEC, FreeBlockBreakPacket::handle);
        registrar.playToServer(ArchPacket.TYPE, ArchPacket.STREAM_CODEC, ArchPacket::handle);
        registrar.playToServer(EllipsePacket.TYPE, EllipsePacket.STREAM_CODEC, EllipsePacket::handle);
        registrar.playToClient(RotationSyncPacket.TYPE, RotationSyncPacket.STREAM_CODEC, RotationSyncPacket::handle);
        registrar.playToClient(SmoothTerrainTogglePacket.TYPE, SmoothTerrainTogglePacket.STREAM_CODEC, SmoothTerrainTogglePacket::handle);
        // Two-way payload (client -> server keeps the command store in sync, server -> client
        // applies expand/contract/shift). playBidirectional registers the type once for both flows.
        registrar.playBidirectional(SelectionSyncPacket.TYPE, SelectionSyncPacket.STREAM_CODEC, SelectionSyncPacket::handle);

    }
}
