package net.buildertools.server;

import io.github.favasur.smoothterrain.config.SmoothTerrainConfig;
import io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl;
import net.buildertools.network.packet.SmoothTerrainTogglePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

/**
 * The {@code smoothTerrain} world rule and the shared backend for the standalone
 * {@code /smoothterrain} command. The game rule is the canonical on/off switch (so vanilla
 * {@code /gamerule smoothTerrain true|false} works out of the box); changing it enables or
 * disables the bundled Smooth Terrain meshing on the server and pushes the new state (plus the
 * current smoothness) to every client. {@code /smoothterrain <0-1>} changes the world's terrain
 * smoothness without touching the enable flag.
 */
public final class SmoothTerrainWorldRules {

    public static final GameRules.Key<GameRules.BooleanValue> SMOOTH_TERRAIN;

    static {
        SMOOTH_TERRAIN = GameRules.register(
                "smoothTerrain",
                GameRules.Category.MISC,
                GameRules.BooleanValue.create(false, SmoothTerrainWorldRules::onChanged));
    }

    private SmoothTerrainWorldRules() {
    }

    /** Touches the class so the game rule is registered (called from the mod's constructor). */
    public static void init() {
    }

    /** Whether Smooth Terrain is currently enabled (mirrors the game rule value). */
    public static boolean enabled() {
        return SmoothTerrainConfig.Server.collisionsEnabled;
    }

    /** The current terrain smoothness in the 0..1 range (0.5 is the default middle). */
    public static float smoothness() {
        return 1.0f - SmoothTerrainConfig.Server.oldSmoothTerrainRoughness;
    }

    /**
     * Backend for {@code /smoothterrain on|off} (and {@code /gamerule smoothTerrain}): writes the
     * game rule, whose {@link #onChanged} callback applies + syncs the change. Setting the value
     * always fires {@code onChanged} (even for a no-op), so this is also safe to call when the
     * state is already requested.
     */
    public static void setEnabled(MinecraftServer server, boolean enabled) {
        apply(server, enabled, smoothness());
    }

    /** Backend for {@code /smoothterrain <0-1>}: changes the smoothness and syncs it to clients. */
    public static void setSmoothness(MinecraftServer server, float smoothness) {
        SmoothTerrainConfigImpl.Server.setSmoothness(smoothness);
        broadcast(server, enabled(), smoothness);
    }

    private static void onChanged(MinecraftServer server, GameRules.BooleanValue value) {
        apply(server, value.get(), smoothness());
    }

    private static void apply(MinecraftServer server, boolean enabled, float smoothness) {
        SmoothTerrainConfigImpl.Server.setEnabled(enabled);
        SmoothTerrainConfigImpl.Server.setSmoothness(smoothness);
        broadcast(server, enabled, smoothness);
    }

    private static void broadcast(MinecraftServer server, boolean enabled, float smoothness) {
        if (server == null) {
            return;
        }
        SmoothTerrainTogglePacket packet = new SmoothTerrainTogglePacket(enabled, smoothness);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }
}