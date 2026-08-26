package net.buildertools.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientPackets {
    private ClientPackets() {
    }

    public static void sendToServer(CustomPacketPayload payload) {
        net.buildertools.network.ModPackets.sendToServer(payload);
    }
}
