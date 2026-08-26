package io.github.favasur.fullslabs.ducks;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public interface ServerPlayerInteractionManagerDuck {
    public void fullslabs$onPlayerDestroyItem(Player var1, ItemStack var2, @Nullable InteractionHand var3);
}

