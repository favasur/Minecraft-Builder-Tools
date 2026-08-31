package net.buildertools.client;

import net.buildertools.util.ArchGeometry;
import net.buildertools.util.EllipseGeometry;
import net.buildertools.util.RotationData;
import io.github.favasur.smoothterrain.mesh.MeshCollisionShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extracts the rotated block's RENDERED geometry as world-space triangles for the exact mesh
 * collision path. The model is pre-rotated by {@link RotatedBlockModel} around the model center
 * (its quads carry the block's true world-space faces), so the triangles are exactly what the
 * player sees - colliding against them removes invisible axis-aligned voxel ledges.
 *
 * <p>Client-only (needs the baked model manager); on a dedicated server, or when the model cannot
 * be built, {@link #triangles} returns null and callers use the server-safe shape fallback.</p>
 */
public final class RotatedBlockTriangles {
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final Map<LocalKey, List<MeshCollisionShape.Tri>> LOCAL_CACHE =
            new ConcurrentHashMap<>();

    private record LocalKey(BlockState state, float yaw, float pitch, long seed) {
    }

    private RotatedBlockTriangles() {
    }

    /**
     * Same lookup with an explicit world for model-offset calculation. The explicit world matters
     * on an integrated server: the immutable quad cache may be shared with the render thread, but
     * {@link BlockState#getOffset(BlockGetter, BlockPos)} must be evaluated against the world that
     * is asking for the collision geometry.
     */
    @Nullable
    public static List<MeshCollisionShape.Tri> triangles(
            BlockState state, float yaw, float pitch, Vec3 center, BlockPos pos, BlockGetter level) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            long seed = state.getSeed(pos);
            LocalKey key = new LocalKey(state, yaw, pitch, seed);
            List<MeshCollisionShape.Tri> local = LOCAL_CACHE.get(key);
            if (local == null) {
                // ModelManager and the baked-model cache are render-thread resources. An
                // integrated server may consume the already-built immutable quad stream, but it
                // must never trigger model loading from the server thread. The collision caller
                // will use its deterministic server-safe fallback until the client has rendered
                // this state/rotation once.
                if (minecraft != null && !minecraft.isSameThread()) {
                    return null;
                }
                RotatedBlockModel model = RotatedBlockModel.get(state, yaw, pitch);
                if (model == null) {
                    return null;
                }
                local = buildLocal(model, state, seed);
                if (local.isEmpty()) {
                    return null;
                }
                if (LOCAL_CACHE.size() >= MAX_CACHE_ENTRIES) {
                    LOCAL_CACHE.clear();
                }
                List<MeshCollisionShape.Tri> previous =
                        LOCAL_CACHE.putIfAbsent(key, local);
                if (previous != null) {
                    local = previous;
                }
            }

            // RotatedBlockRendering applies the block's deterministic model offset before it
            // emits the same quads. Include that offset here as well; otherwise offset-capable
            // blocks (for example plants and a few modded blocks) would render in one place while
            // their exact collision/raycast mesh stayed at the unshifted cell.
            Vec3 offset = Vec3.ZERO;
            if (level != null) {
                offset = state.getOffset(pos);
            }
            List<MeshCollisionShape.Tri> translated =
                    new ArrayList<>(local.size());
            for (MeshCollisionShape.Tri triangle : local) {
                // Local triangles are relative to the model centre; add the actual world centre
                // here so fractional Air Placement positions retain full precision.
                translated.add(triangle.translate(center.x + offset.x, center.y + offset.y,
                        center.z + offset.z));
            }
            return translated;
        } catch (Throwable t) {
            // Best-effort: any failure (dedicated server, unloaded models) falls back to voxels.
            return null;
        }
    }

    /** Builds triangles relative to the model centre, so different world positions share a cache entry. */
    private static List<MeshCollisionShape.Tri> buildLocal(
            RotatedBlockModel model, BlockState state, long seed) {
        List<MeshCollisionShape.Tri> tris = new ArrayList<>();
        for (BakedQuad quad : model.allQuads(state, seed)) {
            addQuad(tris, quad, -0.5, -0.5, -0.5);
        }
        return List.copyOf(tris);
    }

    /** Provider entry point used by both client rendering and integrated-server movement. */
    @Nullable
    public static List<MeshCollisionShape.Tri> triangles(
            RotationData rot, BlockPos cell, BlockGetter level) {
        // Arch / ellipse voussoirs have their own deterministic wedge geometry - never treat
        // them as a rotated baked model.
        if (rot.arch() != null) {
            return ArchGeometry.wedgeTriangles(rot.arch());
        }
        if (rot.ellipse() != null) {
            return EllipseGeometry.wedgeTriangles(rot.ellipse());
        }
        return triangles(rot.state(), rot.yaw(), rot.pitch(), rot.center(cell), cell, level);
    }

    /** Effective-rotation variant for outlines and billboards with an explicit world. */
    @Nullable
    public static List<MeshCollisionShape.Tri> triangles(
            RotationData rot, BlockPos cell, BlockGetter level, float yaw, float pitch) {
        if (rot.arch() != null) {
            return ArchGeometry.wedgeTriangles(rot.arch());
        }
        if (rot.ellipse() != null) {
            return EllipseGeometry.wedgeTriangles(rot.ellipse());
        }
        return triangles(rot.state(), yaw, pitch, rot.center(cell), cell, level);
    }

    private static void addQuad(List<MeshCollisionShape.Tri> tris,
                                BakedQuad quad, double ox, double oy, double oz) {
        double x0 = quad.position(0).x() + ox;
        double y0 = quad.position(0).y() + oy;
        double z0 = quad.position(0).z() + oz;
        double x1 = quad.position(1).x() + ox;
        double y1 = quad.position(1).y() + oy;
        double z1 = quad.position(1).z() + oz;
        double x2 = quad.position(2).x() + ox;
        double y2 = quad.position(2).y() + oy;
        double z2 = quad.position(2).z() + oz;
        double x3 = quad.position(3).x() + ox;
        double y3 = quad.position(3).y() + oy;
        double z3 = quad.position(3).z() + oz;
        tris.add(new MeshCollisionShape.Tri(x0, y0, z0, x1, y1, z1, x2, y2, z2));
        tris.add(new MeshCollisionShape.Tri(x0, y0, z0, x2, y2, z2, x3, y3, z3));
    }
}
