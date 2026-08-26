package io.github.favasur.smoothterrain.fabric;

import net.buildertools.client.ClientEvents;
import net.buildertools.network.FabricNetwork;

public final class ClientInit {
    private ClientInit() {
    }

    public static void register() {
        ClientEvents.initializeGeometry();
        FabricNetwork.registerClient();
    }
}
