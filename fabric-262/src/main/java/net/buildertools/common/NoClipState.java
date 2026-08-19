package net.buildertools.common;

/**
 * Client-side no-clip flag kept in a side-agnostic class so the {@code PlayerMixin} (which runs on
 * both the client and the dedicated server) can read it without touching client-only classes.
 * The server tracks its own copy in {@code BuilderServerHandler}.
 */
public final class NoClipState {
    private static boolean clientEnabled;

    private NoClipState() {
    }

    public static boolean isClientEnabled() {
        return clientEnabled;
    }

    public static void setClientEnabled(boolean enabled) {
        clientEnabled = enabled;
    }
}
