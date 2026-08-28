package net.buildertools.mixin;

import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code GameRules.registerBoolean}, which is not publicly accessible under Fabric's Loom
 * setup (the NeoForge build gets it via its access transformer) so the mod can register its
 * {@code smoothTerrain} world rule.
 */
@Mixin(GameRules.class)
public interface GameRulesAccessor {
    @Invoker("registerBoolean")
    static GameRule<Boolean> buildertools$registerBoolean(
            String id, GameRuleCategory category, boolean defaultValue) {
        throw new AssertionError();
    }
}