package net.neoforged.neoforge.registries;

import net.buildertools.BuilderToolsMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

public class DeferredRegister<T> {
    private final ResourceKey<? extends Registry<T>> registryKey;
    private final String modId;

    protected DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String modId) {
        this.registryKey = registryKey;
        this.modId = modId;
    }

    public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> registryKey, String modId) {
        return new DeferredRegister<>(registryKey, modId);
    }

    public static Items createItems(String modId) {
        return new Items(modId);
    }

    public <I extends T> DeferredHolder<T, I> register(String name, Supplier<? extends I> supplier) {
        // Eagerly construct + register: Fabric freezes the registries after the entrypoints, so a
        // deferred first get() (e.g. from the creative tab) would hit a frozen registry.
        return new DeferredHolder<>(registerValue(name, supplier.get()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <I extends T> I registerValue(String name, I value) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
        if (registryKey.equals(Registries.ENTITY_TYPE)) {
            return (I) Registry.register((Registry) BuiltInRegistries.ENTITY_TYPE, id, value);
        }
        if (registryKey.equals(Registries.SOUND_EVENT)) {
            return (I) Registry.register((Registry) BuiltInRegistries.SOUND_EVENT, id, value);
        }
        return (I) Registry.register((Registry) BuiltInRegistries.ITEM, id, value);
    }

    public void register(net.neoforged.bus.api.IEventBus bus) {
    }

    public static final class Items extends DeferredRegister<Item> {
        private Items(String modId) {
            super(Registries.ITEM, modId);
        }

        public <I extends Item> DeferredItem<I> register(String name, Supplier<? extends I> supplier) {
            return new DeferredItem<>(registerValueForItem(name, supplier.get()));
        }

        private <I extends Item> I registerValueForItem(String name, I value) {
            return Registry.register(BuiltInRegistries.ITEM,
                    ResourceLocation.fromNamespaceAndPath(BuilderToolsMod.MODID, name), value);
        }
    }
}
