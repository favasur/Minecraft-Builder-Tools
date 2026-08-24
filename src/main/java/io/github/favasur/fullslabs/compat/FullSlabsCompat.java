package io.github.favasur.fullslabs.compat;

import io.github.favasur.fullslabs.compat.neoforge.FullSlabsCompatImpl;

public final class FullSlabsCompat {
    public static void init() {
        FullSlabsCompatImpl.platformInit();
    }
}
