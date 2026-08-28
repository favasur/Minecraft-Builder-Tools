package net.buildertools.mixin;

import net.buildertools.server.SmoothTerrainWorldRules;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reacts to {@code /gamerule smoothTerrain true|false} on the 26.2 world-rule system. Every rule
 * change funnels through {@code GameRules#set}, so we only need to check the changed rule against
 * {@link SmoothTerrainWorldRules#SMOOTH_TERRAIN}.
 */
@Mixin(GameRules.class)
public abstract class GameRulesMixin {
    @Inject(method = "set", at = @At("HEAD"))
    private <T> void buildertools$onGameRuleSet(GameRule<T> gameRule, T value,
                                                MinecraftServer server, CallbackInfo ci) {
        if (server != null && SmoothTerrainWorldRules.SMOOTH_TERRAIN != null
                && gameRule == SmoothTerrainWorldRules.SMOOTH_TERRAIN) {
            SmoothTerrainWorldRules.onGameruleChanged(server, (Boolean) value);
        }
    }
}