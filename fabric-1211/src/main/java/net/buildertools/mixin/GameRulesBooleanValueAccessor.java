package net.buildertools.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.BiConsumer;

/**
 * Exposes the package-private {@code GameRules.BooleanValue.create(boolean, BiConsumer)} overload
 * so {@code /gamerule smoothTerrain} can carry a change callback (the NeoForge build gets this
 * via its access transformer; Fabric/Loom does not).
 */
@Mixin(GameRules.BooleanValue.class)
public interface GameRulesBooleanValueAccessor {
    @Invoker("create")
    static GameRules.Type<GameRules.BooleanValue> buildertools$create(
            boolean defaultValue,
            BiConsumer<MinecraftServer, GameRules.BooleanValue> onChanged) {
        throw new AssertionError();
    }
}