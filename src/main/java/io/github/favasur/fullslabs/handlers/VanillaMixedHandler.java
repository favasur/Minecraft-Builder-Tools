package io.github.favasur.fullslabs.handlers;

import io.github.favasur.fullslabs.handlers.MixedHandler;

public class VanillaMixedHandler
implements MixedHandler {
    final boolean valid;
    public static final VanillaMixedHandler INSTANCE = new VanillaMixedHandler(true);
    public static final VanillaMixedHandler INVALID = new VanillaMixedHandler(false);

    private VanillaMixedHandler(boolean valid) {
        this.valid = valid;
    }
}

