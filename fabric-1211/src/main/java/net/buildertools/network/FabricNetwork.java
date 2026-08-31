package net.buildertools.network;

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
import net.buildertools.network.packet.PaintPacket;
import net.buildertools.network.packet.PastePacket;
import net.buildertools.network.packet.PlayerAbilitiesPacket;
import net.buildertools.network.packet.RotationSyncPacket;
import net.buildertools.network.packet.ScatterPacket;
import net.buildertools.network.packet.SelectionCopyPacket;
import net.buildertools.network.packet.SelectionFillPacket;
import net.buildertools.network.packet.SelectionSyncPacket;
import net.buildertools.network.packet.SmoothPacket;
import net.buildertools.network.packet.SmoothTerrainTogglePacket;
import net.buildertools.network.packet.StretchPacket;
import net.buildertools.network.packet.UndoPacket;
import net.buildertools.network.packet.WorldSettingsPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.BiConsumer;

/** Fabric transport for the canonical packet records copied from the NeoForge implementation. */
public final class FabricNetwork {
    private static boolean typesRegistered;
    private static boolean serverReceiversRegistered;
    private static boolean clientReceiversRegistered;

    private FabricNetwork() {
    }

    public static synchronized void register() {
        registerTypes();
        registerServerReceivers();
    }

    public static synchronized void registerClient() {
        registerTypes();
        if (clientReceiversRegistered) {
            return;
        }
        clientReceiversRegistered = true;

        client(SmoothTerrainTogglePacket.TYPE, SmoothTerrainTogglePacket.STREAM_CODEC,
                SmoothTerrainTogglePacket::handle);
        client(RotationSyncPacket.TYPE, RotationSyncPacket.STREAM_CODEC,
                RotationSyncPacket::handle);
        client(SelectionSyncPacket.TYPE, SelectionSyncPacket.STREAM_CODEC,
                SelectionSyncPacket::handle);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static synchronized void registerTypes() {
        if (typesRegistered) {
            return;
        }
        typesRegistered = true;

        c2s(SelectionFillPacket.TYPE, SelectionFillPacket.STREAM_CODEC);
        c2s(SelectionCopyPacket.TYPE, SelectionCopyPacket.STREAM_CODEC);
        c2s(PastePacket.TYPE, PastePacket.STREAM_CODEC);
        c2s(EntityTransformPacket.TYPE, EntityTransformPacket.STREAM_CODEC);
        c2s(EntitySpawnPacket.TYPE, EntitySpawnPacket.STREAM_CODEC);
        c2s(EntityDeletePacket.TYPE, EntityDeletePacket.STREAM_CODEC);
        c2s(EntityDuplicatePacket.TYPE, EntityDuplicatePacket.STREAM_CODEC);
        c2s(PaintPacket.TYPE, PaintPacket.STREAM_CODEC);
        c2s(ScatterPacket.TYPE, ScatterPacket.STREAM_CODEC);
        c2s(SmoothPacket.TYPE, SmoothPacket.STREAM_CODEC);
        c2s(StretchPacket.TYPE, StretchPacket.STREAM_CODEC);
        c2s(UndoPacket.TYPE, UndoPacket.STREAM_CODEC);
        c2s(WorldSettingsPacket.TYPE, WorldSettingsPacket.STREAM_CODEC);
        c2s(PlayerAbilitiesPacket.TYPE, PlayerAbilitiesPacket.STREAM_CODEC);
        c2s(EntityFreezePacket.TYPE, EntityFreezePacket.STREAM_CODEC);
        c2s(OffGridBlockPacket.TYPE, OffGridBlockPacket.STREAM_CODEC);
        c2s(BlockRotationPacket.TYPE, BlockRotationPacket.STREAM_CODEC);
        c2s(FreeBlockBreakPacket.TYPE, FreeBlockBreakPacket.STREAM_CODEC);
        c2s(ArchPacket.TYPE, ArchPacket.STREAM_CODEC);
        c2s(EllipsePacket.TYPE, EllipsePacket.STREAM_CODEC);
        c2s(SelectionSyncPacket.TYPE, SelectionSyncPacket.STREAM_CODEC);

        s2c(RotationSyncPacket.TYPE, RotationSyncPacket.STREAM_CODEC);
        s2c(SmoothTerrainTogglePacket.TYPE, SmoothTerrainTogglePacket.STREAM_CODEC);
        s2c(SelectionSyncPacket.TYPE, SelectionSyncPacket.STREAM_CODEC);
    }

    private static synchronized void registerServerReceivers() {
        if (serverReceiversRegistered) {
            return;
        }
        serverReceiversRegistered = true;

        server(SelectionFillPacket.TYPE, SelectionFillPacket::handle);
        server(SelectionCopyPacket.TYPE, SelectionCopyPacket::handle);
        server(PastePacket.TYPE, PastePacket::handle);
        server(EntityTransformPacket.TYPE, EntityTransformPacket::handle);
        server(EntitySpawnPacket.TYPE, EntitySpawnPacket::handle);
        server(EntityDeletePacket.TYPE, EntityDeletePacket::handle);
        server(EntityDuplicatePacket.TYPE, EntityDuplicatePacket::handle);
        server(PaintPacket.TYPE, PaintPacket::handle);
        server(ScatterPacket.TYPE, ScatterPacket::handle);
        server(SmoothPacket.TYPE, SmoothPacket::handle);
        server(StretchPacket.TYPE, StretchPacket::handle);
        server(UndoPacket.TYPE, UndoPacket::handle);
        server(WorldSettingsPacket.TYPE, WorldSettingsPacket::handle);
        server(PlayerAbilitiesPacket.TYPE, PlayerAbilitiesPacket::handle);
        server(EntityFreezePacket.TYPE, EntityFreezePacket::handle);
        server(OffGridBlockPacket.TYPE, OffGridBlockPacket::handle);
        server(BlockRotationPacket.TYPE, BlockRotationPacket::handle);
        server(FreeBlockBreakPacket.TYPE, FreeBlockBreakPacket::handle);
        server(ArchPacket.TYPE, ArchPacket::handle);
        server(EllipsePacket.TYPE, EllipsePacket::handle);
        server(SelectionSyncPacket.TYPE, SelectionSyncPacket::handle);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends CustomPacketPayload> void c2s(CustomPacketPayload.Type<T> type,
                                                              StreamCodec<?, T> codec) {
        PayloadTypeRegistry.playC2S().register(type, (StreamCodec) codec);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends CustomPacketPayload> void s2c(CustomPacketPayload.Type<T> type,
                                                              StreamCodec<?, T> codec) {
        PayloadTypeRegistry.playS2C().register(type, (StreamCodec) codec);
    }

    private static <T extends CustomPacketPayload> void server(CustomPacketPayload.Type<T> type,
                                                                BiConsumer<T, IPayloadContext> handler) {
        ServerPlayNetworking.registerGlobalReceiver(type,
                (payload, context) -> handler.accept(payload, new ServerContext(context)));
    }

    private static <T extends CustomPacketPayload> void client(CustomPacketPayload.Type<T> type,
                                                                StreamCodec<?, T> codec,
                                                                BiConsumer<T, IPayloadContext> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type,
                (payload, context) -> handler.accept(payload, new ClientContext(context)));
    }

    private record ServerContext(ServerPlayNetworking.Context delegate) implements IPayloadContext {
        @Override
        public void enqueueWork(Runnable work) {
            delegate.server().execute(work);
        }

        @Override
        public net.minecraft.world.entity.player.Player player() {
            return delegate.player();
        }

        @Override
        public PacketFlow flow() {
            return PacketFlow.SERVERBOUND;
        }
    }

    private record ClientContext(ClientPlayNetworking.Context delegate) implements IPayloadContext {
        @Override
        public void enqueueWork(Runnable work) {
            delegate.client().execute(work);
        }

        @Override
        public net.minecraft.world.entity.player.Player player() {
            return delegate.player();
        }

        @Override
        public PacketFlow flow() {
            return PacketFlow.CLIENTBOUND;
        }
    }
}
