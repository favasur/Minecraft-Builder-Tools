package net.buildertools.client;

import net.buildertools.network.ModPackets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientPackets {
    private ClientPackets() {
    }

    public static void sendToServer(Object packet) {
        ModPackets.sendToServer(packet);
    }
}
