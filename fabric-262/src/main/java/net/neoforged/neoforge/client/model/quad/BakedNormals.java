package net.neoforged.neoforge.client.model.quad;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Fabric shim for the NeoForge 26.2 {@code BakedNormals} helper. Packs a quad face normal into the
 * vanilla packed-int encoding and unpacks it back, so the copied baked-quad emission compiles
 * unchanged.
 */
public final class BakedNormals {
    private BakedNormals() {
    }

    public static int computeQuadNormal(Vector3fc p0, Vector3fc p1, Vector3fc p2, Vector3fc p3) {
        Vector3f a = new Vector3f(p1).sub(p0);
        Vector3f b = new Vector3f(p3).sub(p0);
        Vector3f normal = new Vector3f();
        a.cross(b, normal);
        normal.normalize();
        return pack(normal);
    }

    public static Vector3f unpack(int packed, Vector3f dest) {
        float x = (byte) (packed & 0xFF) / 127.0F;
        float y = (byte) ((packed >> 8) & 0xFF) / 127.0F;
        float z = (byte) ((packed >> 16) & 0xFF) / 127.0F;
        dest.set(x, y, z).normalize();
        return dest;
    }

    private static int pack(Vector3f normal) {
        return ((int) (normal.x * 127.0F) & 0xFF)
                | (((int) (normal.y * 127.0F) & 0xFF) << 8)
                | (((int) (normal.z * 127.0F) & 0xFF) << 16);
    }
}
