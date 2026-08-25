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
import net.buildertools.network.packet.PaintPacket;
import net.buildertools.network.packet.PastePacket;
import net.buildertools.network.packet.PlayerAbilitiesPacket;
import net.buildertools.network.packet.RotationSyncPacket;
import net.buildertools.network.packet.ScatterPacket;
import net.buildertools.network.packet.SelectionCopyPacket;
import net.buildertools.network.packet.SelectionFillPacket;
import net.buildertools.network.packet.SelectionSyncPacket;
import net.buildertools.network.packet.SmoothPacket;
import net.buildertools.network.packet.StretchPacket;
import net.buildertools.network.packet.UndoPacket;
import net.buildertools.network.packet.WorldSettingsPacket;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Function;

public final class ModPackets {
    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(BuilderToolsMod.MODID, "main"))
            .networkProtocolVersion(1)
            .simpleChannel();

    private ModPackets() {
    }

    public static void register() {
        register(SelectionFillPacket.class, SelectionFillPacket::encode, SelectionFillPacket::decode, SelectionFillPacket::handle);
        register(SelectionCopyPacket.class, SelectionCopyPacket::encode, SelectionCopyPacket::decode, SelectionCopyPacket::handle);
        register(PastePacket.class, PastePacket::encode, PastePacket::decode, PastePacket::handle);
        register(EntityTransformPacket.class, EntityTransformPacket::encode, EntityTransformPacket::decode, EntityTransformPacket::handle);
        register(EntitySpawnPacket.class, EntitySpawnPacket::encode, EntitySpawnPacket::decode, EntitySpawnPacket::handle);
        register(OffGridBlockPacket.class, OffGridBlockPacket::encode, OffGridBlockPacket::decode, OffGridBlockPacket::handle);
        register(EntityDeletePacket.class, EntityDeletePacket::encode, EntityDeletePacket::decode, EntityDeletePacket::handle);
        register(EntityDuplicatePacket.class, EntityDuplicatePacket::encode, EntityDuplicatePacket::decode, EntityDuplicatePacket::handle);
        register(PaintPacket.class, PaintPacket::encode, PaintPacket::decode, PaintPacket::handle);
        register(ScatterPacket.class, ScatterPacket::encode, ScatterPacket::decode, ScatterPacket::handle);
        register(SmoothPacket.class, SmoothPacket::encode, SmoothPacket::decode, SmoothPacket::handle);
        register(StretchPacket.class, StretchPacket::encode, StretchPacket::decode, StretchPacket::handle);
        register(UndoPacket.class, UndoPacket::encode, UndoPacket::decode, UndoPacket::handle);
        register(WorldSettingsPacket.class, WorldSettingsPacket::encode, WorldSettingsPacket::decode, WorldSettingsPacket::handle);
        register(PlayerAbilitiesPacket.class, PlayerAbilitiesPacket::encode, PlayerAbilitiesPacket::decode, PlayerAbilitiesPacket::handle);
        register(EntityFreezePacket.class, EntityFreezePacket::encode, EntityFreezePacket::decode, EntityFreezePacket::handle);
        // Two-way: client -> server keeps the command store in sync, server -> client applies
        // expand/contract/shift. SimpleChannel messages are bidirectional; the handler branches.
        register(SelectionSyncPacket.class, SelectionSyncPacket::encode, SelectionSyncPacket::decode, SelectionSyncPacket::handle);
        register(BlockRotationPacket.class, BlockRotationPacket::encode, BlockRotationPacket::decode, BlockRotationPacket::handle);
        register(FreeBlockBreakPacket.class, FreeBlockBreakPacket::encode, FreeBlockBreakPacket::decode, FreeBlockBreakPacket::handle);
        register(RotationSyncPacket.class, RotationSyncPacket::encode, RotationSyncPacket::decode, RotationSyncPacket::handle);
    }

    private static int packetIdCounter = 0;

    private static <T> void register(Class<T> type,
                                     BiConsumer<T, FriendlyByteBuf> encoder,
                                     Function<FriendlyByteBuf, T> decoder,
                                     BiConsumer<T, CustomPayloadEvent.Context> handler) {
        // No direction set: messages may flow both ways (client <-> server).
        CHANNEL.messageBuilder(type, packetIdCounter++)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread(handler)
                .add();
    }

    /** Server -> client helper for a specific player. */
    public static void sendToPlayer(Object packet, net.minecraft.server.level.ServerPlayer player) {
        CHANNEL.send(packet, PacketDistributor.PLAYER.with(player));
    }

    /** Client -> server helper. */
    public static void sendToServer(Object packet) {
        CHANNEL.send(packet, PacketDistributor.SERVER.noArg());
    }
}
