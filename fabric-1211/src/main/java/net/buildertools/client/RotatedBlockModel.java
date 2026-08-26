package net.buildertools.client;

import net.buildertools.util.OffGridTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

    private final BakedModel base;
    private final Quaternionf rotation;

    private RotatedBlockModel(BakedModel base, Quaternionf rotation) {
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
        BakedModel base = Minecraft.getInstance().getModelManager()
                .getBlockModelShaper().getBlockModel(state);
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
        BakedModel base = Minecraft.getInstance().getModelManager()
                .getBlockModelShaper().getBlockModel(state);
        return base == null ? null : new RotatedBlockModel(base, rotation);
    }

    /** Returns every transformed quad used by the custom renderer and collision bridge. */
    List<BakedQuad> allQuads(BlockState state, long seed) {
        RandomSource random = RandomSource.create();
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            random.setSeed(seed);
            quads.addAll(rotateAll(base.getQuads(state, direction, random)));
        }
        random.setSeed(seed);
        quads.addAll(rotateAll(base.getQuads(state, null, random)));
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
        int[] vertices = quad.getVertices().clone();
        for (int i = 0; i < 4; i++) {
            int offset = i * 8;
            Vector3f local = new Vector3f(
                    Float.intBitsToFloat(vertices[offset]) - 0.5f,
                    Float.intBitsToFloat(vertices[offset + 1]) - 0.5f,
                    Float.intBitsToFloat(vertices[offset + 2]) - 0.5f);
            Vector3f rotated = rotation.transform(local, new Vector3f());
            vertices[offset] = Float.floatToIntBits(rotated.x + 0.5f);
            vertices[offset + 1] = Float.floatToIntBits(rotated.y + 0.5f);
            vertices[offset + 2] = Float.floatToIntBits(rotated.z + 0.5f);
        }
        return new BakedQuad(vertices, quad.getTintIndex(),
                snap(rotation.transform(quad.getDirection().step(), new Vector3f())),
                quad.getSprite(), quad.isShade());
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
