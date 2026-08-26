package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

/**
 * Holder for a value that {@link DeferredRegister} already constructed and registered. Values are
 * realized eagerly at mod-init time (while the registries are still open); Fabric freezes the
 * registries right after the entrypoints run, so lazily constructing on first {@link #get()} would
 * crash (intrusive holders are unavailable in a frozen registry).
 */
public class DeferredHolder<R, T> implements Supplier<T> {
    private final T value;

    public DeferredHolder(T value) {
        this.value = value;
    }

    @Override
    public T get() {
        return value;
    }
}
