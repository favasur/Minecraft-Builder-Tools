package net.buildertools.client;

import net.buildertools.network.ModPackets;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.network.PacketDistributor;

public final class ClientPackets {
    private ClientPackets() {
    }

    public static void sendToServer(CustomPacketPayload payload) {
        ModPackets.CHANNEL.send(payload, PacketDistributor.SERVER.noArg());
    }
}
