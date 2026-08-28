package net.buildertools.mixin;

import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private static {@code GameRules.register(String, Category, Type)} so the mod can
 * register its {@code smoothTerrain} world rule (the NeoForge build gets this via its access
 * transformer; Fabric/Loom does not).
 */
@Mixin(GameRules.class)
public interface GameRulesAccessor {
    @Invoker("register")
    static <T extends GameRules.Value<T>> GameRules.Key<T> buildertools$register(
            String id,
            GameRules.Category category,
            GameRules.Type<T> type) {
        throw new AssertionError();
    }
}