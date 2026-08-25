package net.buildertools.network.packet;

import net.buildertools.selection.SelectionManager;
import net.buildertools.server.SelectionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.network.CustomPayloadEvent;


/**
 * Syncs the selection region between client and server (client -> server keeps the command store
 * up to date; server -> client updates the in-world box after expand/contract/shift). The region
 * is sent as a min/max box of six ints.
 */
public record SelectionSyncPacket(boolean hasSelection, int xMin, int yMin, int zMin,
                                  int xMax, int yMax, int zMax) {

    public static SelectionSyncPacket fromClient(BlockPos min, BlockPos max) {
        return new SelectionSyncPacket(true, min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ());
    }

    public static SelectionSyncPacket clear() {
        return new SelectionSyncPacket(false, 0, 0, 0, 0, 0, 0);
    }

    public static SelectionSyncPacket decode(FriendlyByteBuf buf) {
        return new SelectionSyncPacket(buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(hasSelection);
        buf.writeVarInt(xMin);
        buf.writeVarInt(yMin);
        buf.writeVarInt(zMin);
        buf.writeVarInt(xMax);
        buf.writeVarInt(yMax);
        buf.writeVarInt(zMax);
    }

    /** Routes to the correct side: server stores the selection, client applies it. */
    public static void handle(SelectionSyncPacket payload, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.isClientSide()) {
                if (payload.hasSelection()) {
                    SelectionManager.applyServerSync(
                            new BlockPos(payload.xMin(), payload.yMin(), payload.zMin()),
                            new BlockPos(payload.xMax(), payload.yMax(), payload.zMax()));
                } else {
                    SelectionManager.applyServerClear();
                }
            } else {
                Player player = ctx.getSender();
                if (player instanceof ServerPlayer serverPlayer) {
                    if (payload.hasSelection()) {
                        SelectionStore.set(serverPlayer,
                                new BlockPos(payload.xMin(), payload.yMin(), payload.zMin()),
                                new BlockPos(payload.xMax(), payload.yMax(), payload.zMax()));
                    } else {
                        SelectionStore.clear(serverPlayer);
                    }
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
