package net.buildertools.server;

import io.github.favasur.smoothterrain.config.SmoothTerrainConfig;
import io.github.favasur.smoothterrain.config.SmoothTerrainConfigImpl;
import net.buildertools.network.packet.SmoothTerrainTogglePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * The {@code smoothTerrain} world rule and the shared backend for the standalone
 * {@code /smoothterrain} command on the 26.2 module. The game rule is the canonical on/off switch
 * (so vanilla {@code /gamerule smoothTerrain true|false} works); changing it fires
 * {@link net.neoforged.neoforge.event.level.GameRuleChangedEvent}, which {@code ServerEvents}
 * routes back here to enable/disable the bundled Smooth Terrain meshing and push the new state to
 * every client. {@code /smoothterrain <0-1>} changes the world's terrain smoothness directly.
 */
public final class SmoothTerrainWorldRules {

    /** The registered game rule, or null if the registry was unavailable at init. */
    public static GameRule<Boolean> SMOOTH_TERRAIN;

    static {
        try {
            SMOOTH_TERRAIN = GameRules.registerBoolean(
                    "smoothTerrain", GameRuleCategory.MISC, false);
        } catch (Throwable t) {
            // Some loader/timing combos freeze the GAME_RULE registry before mod init. The rule is
            // then unavailable to /gamerule, but /smoothterrain still works (it applies directly).
            SMOOTH_TERRAIN = null;
            org.apache.logging.log4j.LogManager.getLogger()
                    .warn("Smooth Terrain: could not register the smoothTerrain game rule", t);
        }
    }

    private SmoothTerrainWorldRules() {
    }

    /** Touches the class so the game rule is registered (called from the mod's constructor). */
    public static void init() {
    }

    /** Whether Smooth Terrain is currently enabled. */
    public static boolean enabled() {
        return SmoothTerrainConfig.Server.collisionsEnabled;
    }

    /** The current terrain smoothness in the 0..1 range (0.5 is the default middle). */
    public static float smoothness() {
        return 1.0f - SmoothTerrainConfig.Server.oldSmoothTerrainRoughness;
    }

    /**
     * Backend for {@code /smoothterrain on|off}: writes the game rule so it stays in sync (its
     * {@code GameRuleChangedEvent} applies + syncs the change); falls back to applying directly if
     * the rule is unavailable.
     */
    public static void setEnabled(MinecraftServer server, boolean enabled) {
        if (server != null && SMOOTH_TERRAIN != null) {
            try {
                server.getGameRules().set(SMOOTH_TERRAIN, enabled, server);
                return;
            } catch (Throwable t) {
                org.apache.logging.log4j.LogManager.getLogger()
                        .warn("Smooth Terrain: could not set the smoothTerrain game rule", t);
            }
        }
        apply(server, enabled, smoothness());
    }

    /** Backend for {@code /smoothterrain <0-1>}: changes the smoothness and syncs it to clients. */
    public static void setSmoothness(MinecraftServer server, float smoothness) {
        SmoothTerrainConfigImpl.Server.setSmoothness(smoothness);
        broadcast(server, enabled(), smoothness);
    }

    /** Called from {@code ServerEvents} when {@link GameRuleChangedEvent} fires for our rule. */
    public static void onGameruleChanged(MinecraftServer server, boolean enabled) {
        apply(server, enabled, smoothness());
    }

    /** Applies the new state on the server and syncs it to every client. */
    public static void apply(MinecraftServer server, boolean enabled, float smoothness) {
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