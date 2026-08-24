package io.github.favasur.fullslabs.config;

import io.github.favasur.fullslabs.util.SlabPlacement;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Controls {

	private static final Map<UUID, SlabPlacement.Mode> modeMap = new HashMap<>();
	private static boolean overlayActive = true;

	public static String MAIN;
	public static KeyMapping cycleMode;
	public static KeyMapping toggleOverlay;

	private Controls() {
	}

	public static SlabPlacement.Mode getPlacementMode(UUID player) {
		return modeMap.computeIfAbsent(player, uuid -> SlabPlacement.Mode.HYBRID);
	}

	private static void cyclePlacementMode(UUID player) {
		modeMap.put(player, getPlacementMode(player).next());
	}

	private static void setPlacementMode(UUID player, SlabPlacement.Mode mode) {
		modeMap.put(player, mode);
	}

	private static void receivePlacementMode(SlabPlacement.Mode mode, net.neoforged.neoforge.network.handling.IPayloadContext context) {
		if (context.player() != null) {
			setPlacementMode(context.player().getUUID(), mode);
		}
	}

	public static boolean isOverlayActive() {
		return overlayActive;
	}

	public static void toggleOverlayActive() {
		overlayActive = !overlayActive;
	}

	/** Server side: accept the placement-mode packet. */
	public static void registerPackets(IEventBus modBus) {
		modBus.addListener((RegisterPayloadHandlersEvent event) -> {
			event.registrar(io.github.favasur.fullslabs.FullSlabs.MODID).optional()
					.playToServer(SlabPlacement.Mode.PACKET_TYPE, SlabPlacement.Mode.PACKET_CODEC, Controls::receivePlacementMode);
		});
	}

	/** Client side: poll the keybinds and sync the mode to the server. */
	public static void onClientTick(Minecraft client) {
		if (client.player == null) {
			return;
		}
		UUID uuid = client.player.getUUID();
		boolean sendVerticalPacket = false;
		while (toggleOverlay.consumeClick()) {
			toggleOverlayActive();
		}
		while (cycleMode.consumeClick()) {
			cyclePlacementMode(uuid);
			sendVerticalPacket = true;
		}
		if (sendVerticalPacket) {
			PacketDistributor.sendToServer(getPlacementMode(uuid));
		}
	}
}
