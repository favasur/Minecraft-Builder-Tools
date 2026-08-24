package net.buildertools.client;

import net.buildertools.util.OffGridTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link BakedModel} whose geometry is pre-rotated by the placement yaw/pitch and whose quads
 * carry the WORLD-space face directions, so the vanilla block pipeline shades every face by where
 * it actually points (bright top, darker sides, AO against the real neighbors) instead of by the
 * unrotated model-space direction. Rendered through {@link RotatedBlockRendering} with the pose
 * only translated to the model center - the geometry is already rotated, and the per-face
 * light/shade follow the world, so a rotated block blends with its surroundings from every
 * viewing angle instead of looking flat or POV-dependent.
 *
 * <p>Built models are cached per (block state, yaw, pitch); billboard blocks (whose facing
 * follows the camera) are built fresh each frame via {@link #build}.
 */
public final class RotatedBlockModel implements BakedModel {
    private static final Map<ModelKey, BakedModel> CACHE = new HashMap<>();
    private static final int MAX_CACHE_ENTRIES = 256;

    private record ModelKey(BlockState state, float yaw, float pitch) {
    }

    private final BakedModel base;
    private final Quaternionf rot;

    private RotatedBlockModel(BakedModel base, Quaternionf rot) {
        this.base = base;
        this.rot = new Quaternionf(rot).normalize();
    }

    /** Cached rotated model for a fixed placement rotation (non-billboard blocks). */
    public static BakedModel get(BlockState state, float yawDeg, float pitchDeg) {
        ModelKey key = new ModelKey(state, yawDeg, pitchDeg);
        BakedModel cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Minecraft mc = Minecraft.getInstance();
        BakedModel base = mc.getModelManager().getBlockModelShaper().getBlockModel(state);
        if (base == null) {
            return null;
        }
        BakedModel rotated = new RotatedBlockModel(base, OffGridTransform.rotation(yawDeg, pitchDeg));
        if (CACHE.size() >= MAX_CACHE_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(key, rotated);
        return rotated;
    }

    /** Fresh rotated model for a camera-dependent (billboard) rotation. */
    public static BakedModel build(BlockState state, Quaternionf quat) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel base = mc.getModelManager().getBlockModelShaper().getBlockModel(state);
        return base == null ? null : new RotatedBlockModel(base, quat);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
        if (direction == null) {
            return rotateAll(base.getQuads(state, null, random));
        }
        // World-space faces of the rotated model come from the model-space faces that land on
        // them under the inverse rotation; the returned quads carry the world direction.
        Direction modelDir = snap(new Quaternionf(rot).conjugate().transform(direction.step(), new Vector3f()));
        return rotateAll(base.getQuads(state, modelDir, random));
    }

    private List<BakedQuad> rotateAll(List<BakedQuad> quads) {
        if (quads.isEmpty()) {
            return quads;
        }
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            out.add(rotateQuad(quad));
        }
        return out;
    }

    /** Rotates the quad's vertices around the model center and its direction into world space. */
    private BakedQuad rotateQuad(BakedQuad quad) {
        int[] v = quad.getVertices().clone();
        for (int i = 0; i < 4; i++) {
            int o = i * 8;
            float x = Float.intBitsToFloat(v[o]) - 0.5f;
            float y = Float.intBitsToFloat(v[o + 1]) - 0.5f;
            float z = Float.intBitsToFloat(v[o + 2]) - 0.5f;
            Vector3f p = rot.transform(new Vector3f(x, y, z), new Vector3f());
            v[o] = Float.floatToIntBits(p.x + 0.5f);
            v[o + 1] = Float.floatToIntBits(p.y + 0.5f);
            v[o + 2] = Float.floatToIntBits(p.z + 0.5f);
            // The BLOCK vertex format packs the normal as 3 signed bytes in the last int's low
            // bytes (byte offsets 28..30 of the 32-byte vertex).
            int packed = v[o + 7];
            Vector3f n = rot.transform(new Vector3f(
                    (byte) (packed & 0xFF), (byte) ((packed >> 8) & 0xFF), (byte) ((packed >> 16) & 0xFF)),
                    new Vector3f());
            v[o + 7] = (packed & 0xFF000000) | (byteOffset(n.z) << 16) | (byteOffset(n.y) << 8) | byteOffset(n.x);
        }
        return new BakedQuad(v, quad.getTintIndex(),
                snap(rot.transform(quad.getDirection().step(), new Vector3f())),
                quad.getSprite(), quad.isShade());
    }

    private static int byteOffset(float f) {
        return ((byte) Math.round(f)) & 0xFF;
    }

    /** The axis direction closest to the given (rotated) normal. */
    private static Direction snap(Vector3f n) {
        Direction best = Direction.NORTH;
        float bestDot = -1.0f;
        for (Direction d : Direction.values()) {
            float dot = n.dot(d.step());
            if (dot > bestDot) {
                bestDot = dot;
                best = d;
            }
        }
        return best;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return base.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return base.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return base.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return base.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return base.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return base.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return base.getOverrides();
    }
}
