package net.buildertools.collision;

import net.buildertools.util.RotationData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;

import java.util.List;

/**
 * Optional bridge from the client renderer to the common collision/raycast code.
 *
 * <p>The server cannot load Minecraft's baked-model classes on a dedicated server.  The common
 * collision code therefore uses the block collision shape as a safe fallback, while the client
 * registers a provider that returns the exact triangles it renders.  Integrated-server movement
 * can use that provider too because both sides share the client JVM; dedicated servers simply keep
 * the fallback geometry.</p>
 */
public final class RotatedCollisionProvider {
    @FunctionalInterface
    public interface Provider {
        List<MeshCollisionShape.Tri> triangles(RotationData rotation, BlockPos cell, BlockGetter level);
    }

    private static volatile Provider provider;

    private RotatedCollisionProvider() {
    }

    /** Registers the client-side rendered-model provider. */
    public static void setProvider(Provider value) {
        provider = value;
    }

    /** Returns rendered triangles, or {@code null} when the client provider is unavailable. */
    public static List<MeshCollisionShape.Tri> triangles(RotationData rotation, BlockPos cell,
                                                           BlockGetter level) {
        Provider value = provider;
        if (value == null) {
            return null;
        }
        try {
            List<MeshCollisionShape.Tri> result = value.triangles(rotation, cell, level);
            return result == null || result.isEmpty() ? null : result;
        } catch (Throwable ignored) {
            // Rendering/model loading is best effort.  The caller will use the server-safe shape.
            return null;
        }
    }
}
