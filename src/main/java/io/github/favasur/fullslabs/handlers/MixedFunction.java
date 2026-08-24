package io.github.favasur.fullslabs.handlers;

import io.github.favasur.fullslabs.util.SlabContext;

@FunctionalInterface
public interface MixedFunction<T> {
    public T apply(SlabContext var1);
}

