package net.buildertools.network.packet;

import net.buildertools.selection.SelectionManager;
import net.buildertools.server.SelectionStore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static net.buildertools.BuilderToolsMod.MODID;

/**
 * Syncs the selection region between client and server (client -> server keeps the command store
 * up to date; server -> client updates the in-world box after expand/contract/shift). The region
 * is sent as a min/max box of six ints.
 */
public record SelectionSyncPacket(boolean hasSelection, int xMin, int yMin, int zMin,
                                  int xMax, int yMax, int zMax) implements CustomPacketPayload {
    public static final Type<SelectionSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "selection_sync"));

    public static final StreamCodec<FriendlyByteBuf, SelectionSyncPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public SelectionSyncPacket decode(FriendlyByteBuf buf) {
            return new SelectionSyncPacket(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, SelectionSyncPacket packet) {
            buf.writeBoolean(packet.hasSelection());
            buf.writeVarInt(packet.xMin());
            buf.writeVarInt(packet.yMin());
            buf.writeVarInt(packet.zMin());
            buf.writeVarInt(packet.xMax());
            buf.writeVarInt(packet.yMax());
            buf.writeVarInt(packet.zMax());
        }
    };

    public static SelectionSyncPacket fromClient(BlockPos min, BlockPos max) {
        return new SelectionSyncPacket(true, min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ());
    }

    public static SelectionSyncPacket clear() {
        return new SelectionSyncPacket(false, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client -> server: store the selection for the /builder commands. */
    public static void handleServer(SelectionSyncPacket payload, ServerPlayNetworking.Context ctx) {
        ServerPlayer player = ctx.player();
        player.server.execute(() -> {
            if (payload.hasSelection()) {
                SelectionStore.set(player,
                        new BlockPos(payload.xMin(), payload.yMin(), payload.zMin()),
                        new BlockPos(payload.xMax(), payload.yMax(), payload.zMax()));
            } else {
                SelectionStore.clear(player);
            }
        });
    }

    /** Server -> client: apply a region change made by a command (expand/contract/shift). */
    public static void handleClient(SelectionSyncPacket payload, ClientPlayNetworking.Context ctx) {
        ctx.client().execute(() -> {
            if (payload.hasSelection()) {
                SelectionManager.applyServerSync(
                        new BlockPos(payload.xMin(), payload.yMin(), payload.zMin()),
                        new BlockPos(payload.xMax(), payload.yMax(), payload.zMax()));
            } else {
                SelectionManager.applyServerClear();
            }
        });
    }
}
