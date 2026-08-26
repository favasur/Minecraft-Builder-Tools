package net.buildertools.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientPackets {
    private ClientPackets() {
    }

    public static void sendToServer(CustomPacketPayload payload) {
        if (Minecraft.getInstance().getConnection() != null) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload);
        }
    }
}
