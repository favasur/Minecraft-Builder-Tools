package io.github.favasur.smoothterrain.neoforge;

/** Forge 26.2 client adapter for the copied Smooth Terrain core. */
public final class ClientInit {
    private ClientInit() {
    }

    public static void register() {
        // 26.2 moved chunk rendering to render-state submission. Builder Tools keeps its own
        // rotated-block renderer target-local; the shared collision core remains enabled.
    }
}
