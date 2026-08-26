package net.buildertools.client;

import net.buildertools.util.OffGridTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A cached baked model with its block-local quads rotated around the model centre. */
public final class RotatedBlockModel {
    private static final Map<ModelKey, RotatedBlockModel> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_ENTRIES = 256;

    private record ModelKey(BlockState state, float yaw, float pitch) {
    }

    private final BlockStateModel base;
    private final Quaternionf rotation;

    private RotatedBlockModel(BlockStateModel base, Quaternionf rotation) {
        this.base = base;
        this.rotation = new Quaternionf(rotation).normalize();
    }

    /** Returns a cached model for a fixed rotation. */
    public static RotatedBlockModel get(BlockState state, float yaw, float pitch) {
        ModelKey key = new ModelKey(state, yaw, pitch);
        RotatedBlockModel cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        BlockStateModel base = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet().get(state);
        if (base == null) {
            return null;
        }
        RotatedBlockModel model = new RotatedBlockModel(base, OffGridTransform.rotation(yaw, pitch));
        if (CACHE.size() >= MAX_CACHE_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(key, model);
        return model;
    }

    /** Builds a non-cached model for a camera-facing billboard. */
    public static RotatedBlockModel build(BlockState state, Quaternionf rotation) {
        BlockStateModel base = Minecraft.getInstance().getModelManager()
                .getBlockStateModelSet().get(state);
        return base == null ? null : new RotatedBlockModel(base, rotation);
    }

    /** Returns every transformed quad used by the custom renderer and collision bridge. */
    List<BakedQuad> allQuads(BlockState state, long seed) {
        RandomSource random = RandomSource.create(seed);
        List<BlockStateModelPart> parts = new ArrayList<>();
        base.collectParts(random, parts);
        List<BakedQuad> quads = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) {
                quads.addAll(rotateAll(part.getQuads(direction)));
            }
            quads.addAll(rotateAll(part.getQuads(null)));
        }
        return quads;
    }

    private List<BakedQuad> rotateAll(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return quads;
        }
        List<BakedQuad> transformed = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            transformed.add(rotateQuad(quad));
        }
        return transformed;
    }

    private BakedQuad rotateQuad(BakedQuad quad) {
        Vector3f[] rotated = new Vector3f[4];
        for (int i = 0; i < 4; i++) {
            Vector3fc local = quad.position(i);
            Vector3f centered = new Vector3f(local.x() - 0.5f, local.y() - 0.5f, local.z() - 0.5f);
            Vector3f world = rotation.transform(centered, new Vector3f());
            rotated[i] = new Vector3f(world.x + 0.5f, world.y + 0.5f, world.z + 0.5f);
        }
        Direction snapped = snap(rotation.transform(quad.direction().step(), new Vector3f()));
        return new BakedQuad(rotated[0], rotated[1], rotated[2], rotated[3],
                quad.packedUV(0), quad.packedUV(1), quad.packedUV(2), quad.packedUV(3),
                snapped, quad.materialInfo());
    }

    private static Direction snap(Vector3f normal) {
        Direction best = Direction.NORTH;
        float bestDot = -1.0f;
        for (Direction direction : Direction.values()) {
            float dot = normal.dot(direction.step());
            if (dot > bestDot) {
                bestDot = dot;
                best = direction;
            }
        }
        return best;
    }
}
