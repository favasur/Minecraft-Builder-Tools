package io.github.favasur.fullslabs.client.models;

import io.github.favasur.fullslabs.block.SlabVertical;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The model for a vertical slab state (1.21.1). Since a vertical slab is the same {@link
 * net.minecraft.world.level.block.SlabBlock} as its horizontal form, this model delegates to the
 * block's own model for the bottom, non-vertical state and re-emits its quads rotated 90 degrees
 * onto the slab's edge. Because the source model is the block's real baked model, this works for
 * every slab in the game - vanilla or modded - with no resource files and no per-slab registry
 * entries.
 *
 * <p>Each emitted quad's cull face is derived from its own transformed geometry (the same
 * calculate-facing rule the block baker uses), never from the parent's cull-face buckets alone.
 * That matters because vanilla slab models bake the top face WITHOUT a cullface - it lives in the
 * un-culled batch - so a lookup of "parent faces culled to up" finds nothing and the vertical
 * slab face that corresponds to the parent's top face used to come out empty (the invisible
 * face). Re-deriving directions from geometry puts every one of the slab's six faces into its
 * proper cull bucket and never emits a null-direction quad.
 */
public final class VerticalSlabModel implements BakedModel {
    private static final Logger LOG = LogManager.getLogger("FullSlabs");
    /** Vertex stride of the BLOCK vertex format (position, color, uv0, uv2, normal). */
    private static final int VERTEX_STRIDE = 8;

    private final BakedModel parent;
    private final Direction occupied;
    private final Matrix4f transform;
    private final Quaternionf rotation;
    /** Parent cull face -> transformed cull face (forward rotation). */
    private final Map<Direction, Direction> forward;
    /** Requested cull face -> parent cull face (inverse rotation). */
    private final Map<Direction, Direction> inverse;
    /** One-shot diagnostic: log the parent's quad counts per bucket on first render. */
    private boolean parentBakeLogged;
    private long lastDebugLog;

    public VerticalSlabModel(BakedModel parent, Direction occupied) {
        this.parent = parent;
        this.occupied = occupied;
        this.rotation = new Quaternionf();
        Vector3f translation = new Vector3f();
        switch (occupied) {
            case NORTH -> {
                this.rotation.rotationX(-(float) Math.PI / 2.0F);
                translation.set(0.0F, 0.0F, 0.5F);
            }
            case SOUTH -> {
                this.rotation.rotationX((float) Math.PI / 2.0F);
                translation.set(0.0F, 1.0F, 0.5F);
            }
            case WEST -> {
                this.rotation.rotationZ((float) Math.PI / 2.0F);
                translation.set(0.5F, 0.0F, 0.0F);
            }
            case EAST -> {
                this.rotation.rotationZ(-(float) Math.PI / 2.0F);
                translation.set(0.5F, 1.0F, 0.0F);
            }
            default -> throw new IllegalArgumentException("Invalid vertical direction " + occupied);
        }
        // M = T * R so that p' = R(p) + t. The baked quad positions are block-local 0..1
        // units (the layer renderer and RotatedBlockModel rotate around 0.5), so the
        // half-block translations are 0.5/1.0, NOT 8/16: using 8/16 placed the slab about
        // eight blocks away from its cell and made every vertical slab invisible.
        this.transform = new Matrix4f().translate(translation).rotate(this.rotation);
        this.forward = directionMap(this.rotation);
        this.inverse = directionMap(this.rotation.conjugate(new Quaternionf()));
    }

    private static Map<Direction, Direction> directionMap(Quaternionf rot) {
        Map<Direction, Direction> map = new EnumMap<>(Direction.class);
        for (Direction from : Direction.values()) {
            Vector3f v = new Vector3f(from.step()).rotate(rot);
            int x = Math.round(v.x());
            int y = Math.round(v.y());
            int z = Math.round(v.z());
            for (Direction to : Direction.values()) {
                Vector3f step = to.step();
                if (Math.round(step.x()) == x && Math.round(step.y()) == y && Math.round(step.z()) == z) {
                    map.put(from, to);
                    break;
                }
            }
        }
        return map;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        BlockState flat = SlabVertical.flat(state);
        List<BakedQuad> out = new ArrayList<>();
        // Quads whose parent cull face rotates onto this slab face (forward rotation).
        if (side != null) {
            Direction parentSide = this.inverse.get(side);
            for (BakedQuad quad : this.parent.getQuads(flat, parentSide, random)) {
                out.add(transformQuad(quad));
            }
        }
        // The parent's un-culled batch: vanilla slab models bake the top face without a cullface,
        // so the inverse lookup above finds nothing for the slab face that corresponds to the
        // parent's top. After the 90-degree rotation that face is a real side face - re-cull it
        // from the transformed geometry and emit it into the right bucket, otherwise that
        // vertical-slab face silently disappears (the "invisible texture" bug).
        for (BakedQuad quad : this.parent.getQuads(flat, null, random)) {
            BakedQuad transformed = transformQuad(quad);
            if (deriveDirection(transformed.getVertices()) == side) {
                out.add(transformed);
            }
        }
        if (LOG.isDebugEnabled()) {
            long now = System.currentTimeMillis();
            if (now - this.lastDebugLog > 1000L) {
                this.lastDebugLog = now;
                LOG.debug("Vertical slab {}: cullFace={} produced {} quads", this.occupied, side, out.size());
                if (!this.parentBakeLogged) {
                    this.parentBakeLogged = true;
                    for (Direction d : Direction.values()) {
                        LOG.debug("Vertical-slab parent bake: flat={} cull={} quads={}",
                                flat, d, this.parent.getQuads(flat, d, random).size());
                    }
                    LOG.debug("Vertical-slab parent bake: flat={} cull=null quads={}",
                            flat, this.parent.getQuads(flat, null, random).size());
                }
            }
        }
        return out;
    }

    private BakedQuad transformQuad(BakedQuad quad) {
        int[] source = quad.getVertices();
        int[] vertices = source.clone();
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * VERTEX_STRIDE;
            Vector3f position = new Vector3f(
                    Float.intBitsToFloat(source[base]),
                    Float.intBitsToFloat(source[base + 1]),
                    Float.intBitsToFloat(source[base + 2])).mulPosition(this.transform);
            vertices[base] = Float.floatToRawIntBits(position.x());
            vertices[base + 1] = Float.floatToRawIntBits(position.y());
            vertices[base + 2] = Float.floatToRawIntBits(position.z());
            int packedNormal = source[base + 7];
            float nx = (byte) (packedNormal & 0xFF) / 127.0F;
            float ny = (byte) ((packedNormal >> 8) & 0xFF) / 127.0F;
            float nz = (byte) ((packedNormal >> 16) & 0xFF) / 127.0F;
            Vector3f normal = new Vector3f(nx, ny, nz).rotate(this.rotation);
            vertices[base + 7] = packNormal(normal);
        }
        // Derive the cull face from the transformed geometry instead of trusting the parent's
        // bucket metadata, so un-culled parent quads (the vanilla slab's top face) get a real
        // direction here.
        Direction direction = deriveDirection(vertices);
        return new BakedQuad(vertices, quad.getTintIndex(), direction, quad.getSprite(), quad.isShade());
    }

    /**
     * The cull face of a quad derived from its vertex positions - the same rule the block baker
     * uses ({@code FaceBakery#calculateFacing}): normal = (v2 - v1) x (v0 - v1), then the
     * direction with the strongest positive dot product. Rotations preserve winding, so the
     * transformed quad yields the correct outward face.
     */
    private static Direction deriveDirection(int[] vertices) {
        float x0 = Float.intBitsToFloat(vertices[0]);
        float y0 = Float.intBitsToFloat(vertices[1]);
        float z0 = Float.intBitsToFloat(vertices[2]);
        float x1 = Float.intBitsToFloat(vertices[8]);
        float y1 = Float.intBitsToFloat(vertices[9]);
        float z1 = Float.intBitsToFloat(vertices[10]);
        float x2 = Float.intBitsToFloat(vertices[16]);
        float y2 = Float.intBitsToFloat(vertices[17]);
        float z2 = Float.intBitsToFloat(vertices[18]);
        float ax = x0 - x1, ay = y0 - y1, az = z0 - z1;
        float bx = x2 - x1, by = y2 - y1, bz = z2 - z1;
        float nx = by * az - bz * ay;
        float ny = bz * ax - bx * az;
        float nz = bx * ay - by * ax;
        float len = (float) Math.sqrt((double) nx * nx + (double) ny * ny + (double) nz * nz);
        if (!Float.isFinite(nx) || !Float.isFinite(ny) || !Float.isFinite(nz) || len < 1.0E-6F) {
            return Direction.UP;
        }
        Direction best = Direction.UP;
        float bestDot = -1.0F;
        for (Direction direction : Direction.values()) {
            float dot = (nx * direction.getStepX() + ny * direction.getStepY() + nz * direction.getStepZ()) / len;
            if (dot > bestDot) {
                bestDot = dot;
                best = direction;
            }
        }
        return best;
    }

    private static int packNormal(Vector3f normal) {
        int x = (int) Math.round(normal.x() * 127.0F) & 0xFF;
        int y = (int) Math.round(normal.y() * 127.0F) & 0xFF;
        int z = (int) Math.round(normal.z() * 127.0F) & 0xFF;
        return x | y << 8 | z << 16;
    }

    @Override
    public boolean useAmbientOcclusion() {
        return this.parent.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return this.parent.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return this.parent.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return this.parent.isCustomRenderer();
    }

    @Override
    public TextureAtlasSprite getParticleIcon() {
        return this.parent.getParticleIcon();
    }

    @Override
    public ItemTransforms getTransforms() {
        return this.parent.getTransforms();
    }

    @Override
    public ItemOverrides getOverrides() {
        return this.parent.getOverrides();
    }
}
