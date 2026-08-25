package net.buildertools.mixin;

import net.buildertools.util.ApiCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Version-gates the Builder Tools core mixins so the same jar runs on every 1.21.x minor version
 * (1.21.1 … 1.21.11). Minecraft's client APIs changed shape several times across the 1.21 line
 * (item rendering, block breaking, mouse handling, the HUD render, block-state flags); a mixin
 * whose target no longer exists would crash the game on load, so each mixin is applied only when
 * {@link ApiCompat} confirms its target method shape is present on the running version.
 */
public final class BuilderToolsMixinPlugin implements IMixinConfigPlugin {

	boolean shouldApply(String mixinClassName) {
		String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
		return switch (name) {
			case "ItemRendererMixin" -> ApiCompat.itemRendererRenderV1();
			case "MultiPlayerGameModeMixin", "ClientPlayerInteractionManagerMixin" -> ApiCompat.startDestroyBlockV1();
			case "MouseHandlerMixin" -> ApiCompat.mouseHandlerV1();
			case "GuiMixin" -> ApiCompat.guiRenderV1();
			case "LevelMixin" -> ApiCompat.levelSetBlock4Arg();
			default -> true;
		};
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return shouldApply(mixinClassName);
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
