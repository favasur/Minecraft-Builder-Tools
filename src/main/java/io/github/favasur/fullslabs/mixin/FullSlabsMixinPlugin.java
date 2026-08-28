package io.github.favasur.fullslabs.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * 1.21.1 FullSlabs mixin plugin: gates client-only mixins (e.g. {@code client.BlockModelShaperMixin})
 * on dedicated servers, where the client classes are absent, and skips any target that cannot be
 * loaded on the current side. Every mixin in the config targets stable 1.21.1 APIs.
 */
public final class FullSlabsMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Check classpath presence WITHOUT loading the class: Class.forName would eagerly load
        // e.g. BlockBehaviour, which then trips MixinTargetAlreadyLoadedException for the next
        // mixin targeting the same class (both BlockSupportShapeMixin and SlabLightMixin target
        // BlockBehaviour). A resource lookup answers the same "is the target present on this
        // side" question without poisoning later mixins.
        String resource = targetClassName.replace('.', '/') + ".class";
        return FullSlabsMixinPlugin.class.getClassLoader().getResource(resource) != null;
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
