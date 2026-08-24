package io.github.favasur.fullslabs.variants;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Item for a generated variant. Its display name is the base block's name plus a suffix, so e.g.
 * "Oak Planks" becomes "Oak Planks Slab" without needing per-variant lang entries.
 */
public class VariantItem extends BlockItem {

	private final Block base;
	private final String suffix;

	public VariantItem(Block block, Block base, String suffix, Properties properties) {
		super(block, properties);
		this.base = base;
		this.suffix = suffix;
	}

	@Override
	public Component getName(ItemStack stack) {
		return Component.translatable(this.base.getDescriptionId()).append(" " + suffix);
	}
}
